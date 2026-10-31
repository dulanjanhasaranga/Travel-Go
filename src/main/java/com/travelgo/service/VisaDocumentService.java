package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.enums.VisaStatus;
import com.travelgo.repository.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final VisaDocumentStorage storage;
    private final jakarta.persistence.EntityManager em;
    private final VisaChecklistService checklists;
    private final UserRepository users;
    private final DocumentUploadQuota quota;
    private final VisaDocumentScanner scanner;
    private final DocumentUploadLimits limits;
    public VisaDocumentService(VisaDocumentRepository repository, VisaApplicationRepository visas, WorkflowRules rules,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String directory, jakarta.persistence.EntityManager em, VisaChecklistService checklists,
            UserRepository users, DocumentUploadQuota quota, VisaDocumentScanner scanner) {
        this(repository,visas,rules,directory,em,checklists,users,quota,scanner,new FilesystemVisaDocumentStorage(directory));
    }
    public VisaDocumentService(VisaDocumentRepository repository, VisaApplicationRepository visas, WorkflowRules rules,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String directory, jakarta.persistence.EntityManager em, VisaChecklistService checklists,
            UserRepository users, DocumentUploadQuota quota, VisaDocumentScanner scanner, VisaDocumentStorage storage) {
        this(repository,visas,rules,directory,em,checklists,users,quota,scanner,storage,new DocumentUploadLimits());
    }
    @Autowired
    public VisaDocumentService(VisaDocumentRepository repository, VisaApplicationRepository visas, WorkflowRules rules,
            @Value("${travelgo.upload-directory:uploads/visa-documents}") String directory, jakarta.persistence.EntityManager em, VisaChecklistService checklists,
            UserRepository users, DocumentUploadQuota quota, VisaDocumentScanner scanner, VisaDocumentStorage storage,
            DocumentUploadLimits limits) {
        this.repository=repository; this.visas=visas; this.rules=rules; this.storage=storage; this.em=em;
        this.checklists=checklists; this.users=users;
        this.quota=quota; this.scanner=scanner; this.limits=Objects.requireNonNull(limits);
    }
    public DocumentUploadLimits getUploadLimits() { return limits; }
    public List<VisaDocument> findByVisaApplicationId(Long id) { return repository.findByVisaApplication_Id(id); }
    public Optional<VisaDocument> findById(Long id) { return repository.findById(id); }
    @Transactional(readOnly = true)
    public List<VisaDocument> findForCurrentCustomer() {
        rules.requireRole("CUSTOMER");
        return repository.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(rules.actor().getId());
    }
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
            if (!head.startsWith("%PDF-") || !tail.contains("%%EOF")) throw new IllegalArgumentException("This PDF could not be validated. Export a new PDF and upload it again.");
        } else {
            try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers=ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("This image could not be read. Upload a valid JPEG or PNG file.");
                var reader=readers.next();
                try {
                    reader.setInput(input);
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(extension.equals("jpg") ? format.equals("jpeg") || format.equals("jpg") : format.equals("png"))
                            || (long)reader.getWidth(0)*reader.getHeight(0)>limits.getMaxImagePixels())
                        throw new IllegalArgumentException("Upload a JPEG or PNG in its original format, with no more than "+limits.getMaxImageSizeLabel()+".");
                    if (reader.read(0)==null) throw new IllegalArgumentException("This image could not be read. Upload a valid JPEG or PNG file.");
                } finally { reader.dispose(); }
            }
        }
        return extension;
    }
    public VisaDocument upload(Long visaId, String name, MultipartFile file) throws IOException {
        return upload(visaId,name,file,null);
    }
    public VisaDocument upload(Long visaId, String name, MultipartFile file, Long checklistItemId) throws IOException {
        rules.requireRole("CUSTOMER");
        // Match booking creation's account-before-booking order. Serialize quota
        // admission across all visa applications belonging to this customer.
        User actor=rules.actor();
        User lockedActor=users.lockAccount(actor.getId()).orElseThrow(() -> new IllegalArgumentException("Account not found."));
        em.refresh(lockedActor);
        if (!lockedActor.isActive() || !rules.role(lockedActor,"CUSTOMER"))
            throw new org.springframework.security.access.AccessDeniedException("Access denied.");
        VisaApplication v=lockedVisa(visaId); rules.owner(v.getBooking()); rules.requireDocumentsOpen(v);
        boolean alreadySubmitted=v.getStatus()==VisaStatus.DOCUMENTS_SUBMITTED;
        VisaChecklistItem requirement=checklists.resolveItem(v,checklistItemId);
        if (name==null || name.isBlank() || name.length()>255) throw new IllegalArgumentException("Enter a document name using up to 255 characters.");
        if (file==null || file.isEmpty() || file.getSize()<1 || file.getSize()>limits.getMaxFileBytes()) throw invalidFileSize();
        DocumentUploadQuota.Usage usage=quota.inspect(
                repository.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(actor.getId(),
                    org.springframework.data.domain.PageRequest.of(0,quota.inspectionLimit())),v.getId(),storage);
        byte[] data;
        try (var input=file.getInputStream()) { data=input.readNBytes((int)limits.getMaxFileBytes()+1); }
        if (data.length==0 || data.length>limits.getMaxFileBytes()) throw invalidFileSize();
        quota.admit(usage,data.length);
        String extension=validateFile(file,data);
        scanner.scan(data);
        var stored=storage.store(data,extension);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                public void afterCompletion(int status) {
                    if (status!=STATUS_COMMITTED) cleanup(stored);
                }
            });
        } catch (RuntimeException unavailableTransaction) {
            cleanup(stored);
            throw unavailableTransaction;
        }
        VisaDocument d=new VisaDocument(); d.setVisaApplication(v); d.setDocumentName(name.trim()); d.setDocumentUrl(stored.reference());
        d.setChecklistItem(requirement);
        repository.saveAndFlush(d);
        rules.transition(v,VisaStatus.DOCUMENTS_SUBMITTED,"Documents uploaded for officer review.");
        // The first upload already notifies officers through the status transition.
        if (alreadySubmitted) {
            users.findAll().stream().filter(u -> u.isActive() && rules.role(u,"VISA_OFFICER"))
                .forEach(u -> rules.notify(u,"VISA_SUBMITTED","New visa document uploaded",
                    "Booking #"+v.getBooking().getId()+" has an additional document ready for review.","VISA",v.getId()));
        }
        return d;
    }
    private IllegalArgumentException invalidFileSize() {
        return new IllegalArgumentException("Choose a non-empty file no larger than "+limits.getMaxFileSizeLabel()+".");
    }
    private void cleanup(VisaDocumentStorage.StoredDocument stored) {
        try { stored.cleanup().run(); }
        catch(IOException failure) {
            org.slf4j.LoggerFactory.getLogger(VisaDocumentService.class).error("Private document cleanup needs operator review.");
        }
    }
    @Transactional(readOnly=true)
    public Download download(Long id) {
        try {
            User user=rules.actor();
            // The private endpoint deliberately returns the same 404 for all
            // denials. Officer role alone must not bypass revoked visa grants.
            boolean officer=rules.role(user,"VISA_OFFICER") && user.getRole()!=null
                && user.getRole().getPermissions()!=null
                && user.getRole().getPermissions().stream().filter(Objects::nonNull)
                    .anyMatch(permission -> Set.of("VISA_VIEW","VISA_MANAGE").contains(permission.getPermissionName()));
            boolean customer=rules.role(user,"CUSTOMER");
            if (!officer && !customer) throw new IllegalStateException();
            VisaDocument d=repository.findById(id).orElseThrow();
            if (!officer && !d.getVisaApplication().getBooking().getUser().getId().equals(user.getId())) throw new IllegalStateException();
            var document=storage.read(d.getDocumentUrl());
            String filename="visa-document-"+id+"."+document.extension();
            byte[] content=document.bytes();
            if (content.length==0 || content.length>limits.getMaxFileBytes()) throw new IllegalStateException();
            scanner.scan(content);
            return new Download(content, filename);
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found."); }
    }
    public record Download(byte[] content, String filename) {}
}
