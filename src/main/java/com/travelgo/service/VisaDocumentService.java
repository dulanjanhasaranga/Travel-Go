package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.enums.VisaStatus;
import com.travelgo.repository.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class VisaDocumentService {
    private final VisaDocumentRepository repository;
    private final VisaApplicationRepository visas;
    private final WorkflowRules rules;
    private final Path root;
    private final jakarta.persistence.EntityManager em;
    public VisaDocumentService(VisaDocumentRepository repository, VisaApplicationRepository visas, WorkflowRules rules,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String directory, jakarta.persistence.EntityManager em) {
        this.repository=repository; this.visas=visas; this.rules=rules; this.root=Path.of(directory).toAbsolutePath().normalize(); this.em=em;
    }
    public List<VisaDocument> findByVisaApplicationId(Long id) { return repository.findByVisaApplication_Id(id); }
    public Optional<VisaDocument> findById(Long id) { return repository.findById(id); }
    private VisaApplication lockedVisa(Long id) {
        VisaApplication v=visas.findById(id).orElseThrow(() -> new IllegalArgumentException("Visa application not found."));
        rules.lock(v.getBooking().getId()); em.refresh(v); return v;
    }
    public VisaDocument verifyDocument(Long id) {
        rules.requireRole("VISA_OFFICER");
        VisaDocument d=repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Document not found."));
        VisaApplication v=lockedVisa(d.getVisaApplication().getId()); rules.requireDocumentsOpen(v);
        if (v.getStatus() == VisaStatus.DOCUMENTS_REQUIRED)
            throw new IllegalStateException("Wait for the requested documents to be uploaded before verification.");
        d.setVerified(true); return repository.save(d);
    }
    private String validateFile(MultipartFile file, byte[] data) throws IOException {
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        String extension=name.contains(".") ? name.substring(name.lastIndexOf('.')+1) : "";
        if (extension.equals("jpeg")) extension="jpg";
        if (!Set.of("pdf", "png", "jpg").contains(extension)) throw new IllegalArgumentException("Use a PDF, JPEG, or PNG file.");
        if (extension.equals("pdf")) {
            String head=new String(data, 0, Math.min(data.length,8), StandardCharsets.ISO_8859_1);
            String tail=new String(data, Math.max(0,data.length-1024), Math.min(data.length,1024),StandardCharsets.ISO_8859_1);
            if (!head.startsWith("%PDF-") || !tail.contains("%%EOF")) throw new IllegalArgumentException("Invalid PDF content.");
        } else {
            try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers=ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("Invalid image content.");
                var reader=readers.next();
                try {
                    reader.setInput(input);
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(extension.equals("jpg") ? format.equals("jpeg") || format.equals("jpg") : format.equals("png"))
                            || (long)reader.getWidth(0)*reader.getHeight(0)>25000000L)
                        throw new IllegalArgumentException("Image format or dimensions are not supported.");
                    if (reader.read(0)==null) throw new IllegalArgumentException("Invalid image content.");
                } finally { reader.dispose(); }
            }
        }
        return extension;
    }
    public VisaDocument upload(Long visaId, String name, MultipartFile file) throws IOException {
        VisaApplication v=lockedVisa(visaId); rules.owner(v.getBooking()); rules.requireDocumentsOpen(v);
        if (name==null || name.isBlank() || name.length()>255) throw new IllegalArgumentException("Enter a document name up to 255 characters.");
        if (file==null || file.isEmpty() || file.getSize()>10L*1024*1024) throw new IllegalArgumentException("Choose a file of at most 10 MB.");
        byte[] data;
        try (var input=file.getInputStream()) { data=input.readNBytes(10*1024*1024+1); }
        if (data.length==0 || data.length>10*1024*1024) throw new IllegalArgumentException("Choose a file of at most 10 MB.");
        String extension=validateFile(file,data);
        Files.createDirectories(root); Path actualRoot=root.toRealPath();
        Path target=actualRoot.resolve(UUID.randomUUID()+"."+extension);
        try { Files.write(target,data,StandardOpenOption.CREATE_NEW); }
        catch (IOException e) { Files.deleteIfExists(target); throw e; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCompletion(int status) {
                if (status!=STATUS_COMMITTED) {
                    try { Files.deleteIfExists(target); }
                    catch(IOException e) { org.slf4j.LoggerFactory.getLogger(VisaDocumentService.class).error("Could not clean up rolled-back document {}", target,e); }
                }
            }
        });
        VisaDocument d=new VisaDocument(); d.setVisaApplication(v); d.setDocumentName(name.trim()); d.setDocumentUrl(target.toString());
        repository.saveAndFlush(d);
        rules.transition(v,VisaStatus.DOCUMENTS_SUBMITTED,"Documents uploaded for officer review."); return d;
    }
    @Transactional(readOnly=true)
    public Download download(Long id) {
        try {
            User user=rules.actor();
            VisaDocument d=repository.findById(id).orElseThrow();
            if (!rules.role(user,"VISA_OFFICER") && !(rules.role(user,"CUSTOMER") && d.getVisaApplication().getBooking().getUser().getId().equals(user.getId()))) throw new IllegalStateException();
            Path stored=Path.of(d.getDocumentUrl()).toAbsolutePath().normalize();
            Path actualRoot=root.toRealPath();
            if (!stored.startsWith(root) || !stored.toRealPath().startsWith(actualRoot) || !Files.isRegularFile(stored) || Files.size(stored)>10L*1024*1024) throw new IllegalStateException();
            String extension=stored.getFileName().toString().toLowerCase(Locale.ROOT);
            extension=extension.contains(".") ? extension.substring(extension.lastIndexOf('.')+1) : "bin";
            if (!Set.of("pdf","jpg","jpeg","png").contains(extension)) extension="bin";
            String filename="visa-document-"+id+"."+extension;
            return new Download(Files.readAllBytes(stored), filename);
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found."); }
    }
    public record Download(byte[] content, String filename) {}
}
