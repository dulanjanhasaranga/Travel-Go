package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.VisaStatus;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisaDocumentAdmissionTests {
    @TempDir Path root;
    VisaDocumentRepository documents=mock(VisaDocumentRepository.class);
    VisaApplicationRepository visas=mock(VisaApplicationRepository.class);
    WorkflowRules rules=mock(WorkflowRules.class);
    UserRepository users=mock(UserRepository.class);
    VisaDocumentScanner scanner=mock(VisaDocumentScanner.class);
    VisaDocumentStorage storage=mock(VisaDocumentStorage.class);
    DocumentUploadQuota quota=spy(new DocumentUploadQuota(2,3,10*1024*1024L,20*1024*1024L));
    jakarta.persistence.EntityManager em=mock(jakarta.persistence.EntityManager.class);
    VisaDocumentService service;
    User actor;
    VisaApplication visa;
    @BeforeEach void fixture() {
        actor=new User();actor.setId(7L);
        Booking booking=new Booking();booking.setId(11L);booking.setUser(actor);
        visa=new VisaApplication();visa.setId(13L);visa.setBooking(booking);visa.setStatus(VisaStatus.PENDING_DOCUMENTS);
        when(rules.actor()).thenReturn(actor);
        when(rules.role(actor,"CUSTOMER")).thenReturn(true);
        when(users.lockAccount(7L)).thenReturn(Optional.of(actor));
        when(visas.findById(13L)).thenReturn(Optional.of(visa));
        when(documents.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(eq(7L),any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of());
        service=new VisaDocumentService(documents,visas,rules,root.toString(),em,mock(VisaChecklistService.class),users,
                quota,scanner);
    }
    VisaDocument stored(long id,Path path) {
        var document=new VisaDocument();document.setId(id);document.setVisaApplication(visa);document.setDocumentUrl(path.toString());return document;
    }
    MockMultipartFile pdf() { return new MockMultipartFile("file","test.pdf","application/pdf","%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII)); }
    @Test void quotaRejectionPrecedesIncomingStreamAndPersistsNothing() throws Exception {
        when(documents.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(eq(7L),any(org.springframework.data.domain.Pageable.class))).thenReturn(
                List.of(stored(1,root.resolve("not-read-a")),stored(2,root.resolve("not-read-b"))));
        MultipartFile incoming=mock(MultipartFile.class);when(incoming.getSize()).thenReturn(1L);
        assertThrows(IllegalArgumentException.class,()->service.upload(13L,"Passport",incoming));
        verify(incoming,never()).getInputStream();verify(documents,never()).saveAndFlush(any());verifyNoInteractions(scanner);
        try(var files=Files.list(root)) {assertEquals(0,files.count());}
    }
    @Test void ownerAccountIsLockedBeforeBookingAndQuotaLookup() throws Exception {
        when(documents.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(eq(7L),any(org.springframework.data.domain.Pageable.class))).thenReturn(
                List.of(stored(1,root.resolve("not-read-a")),stored(2,root.resolve("not-read-b"))));
        assertThrows(IllegalArgumentException.class,()->service.upload(13L,"Passport",pdf()));
        var order=inOrder(users,visas,rules,documents);
        order.verify(users).lockAccount(7L);order.verify(visas).findById(13L);
        order.verify(rules).lock(11L);
        order.verify(documents).findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(eq(7L),any(org.springframework.data.domain.Pageable.class));
    }
    @Test void scannerFailureRejectsBeforeStoringFileOrChangingWorkflow() throws Exception {
        doThrow(new IOException("Document could not be scanned.")).when(scanner).scan(any());
        assertThrows(IOException.class,()->service.upload(13L,"Passport",pdf()));
        verify(documents,never()).saveAndFlush(any());verify(rules,never()).transition(any(),any(),any());
        try(var files=Files.list(root)) {assertEquals(0,files.count());}
    }
    @Test void scannerReceivesTheExactAuthorizedDownloadBytes() throws Exception {
        byte[] data="%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        Path file=Files.write(root.resolve("retained.pdf"),data);
        when(documents.findById(1L)).thenReturn(Optional.of(stored(1,file)));
        when(rules.role(actor,"CUSTOMER")).thenReturn(true);
        assertArrayEquals(data,service.download(1L).content());
        verify(scanner).scan(argThat(bytes->java.util.Arrays.equals(data,bytes)));
    }
    @Test void unavailableScannerCannotReleaseExistingDocument() throws Exception {
        Path file=Files.write(root.resolve("retained.pdf"),pdf().getBytes());
        when(documents.findById(1L)).thenReturn(Optional.of(stored(1,file)));
        when(rules.role(actor,"CUSTOMER")).thenReturn(true);
        doThrow(new IOException("scanner unavailable")).when(scanner).scan(any());
        var failure=assertThrows(ResponseStatusException.class,()->service.download(1L));
        assertEquals(404,failure.getStatusCode().value());assertEquals("Document not found.",failure.getReason());
    }
    @Test void unauthorizedDownloadsNeverReachScannerOrFileRead() {
        when(rules.role(actor,"CUSTOMER")).thenReturn(false);
        when(documents.findById(1L)).thenReturn(Optional.of(stored(1,root.resolve("missing-private.pdf"))));
        assertThrows(ResponseStatusException.class,()->service.download(1L));verifyNoInteractions(scanner);
    }

    @Test void lowerConfiguredFileCapRejectsBeforeOpeningStreamQuotaInspectionOrStorage() throws Exception {
        service=limited(new DocumentUploadLimits(32,8000000));
        MultipartFile incoming=mock(MultipartFile.class);when(incoming.getSize()).thenReturn(33L);

        var failure=assertThrows(IllegalArgumentException.class,()->service.upload(13L,"Passport",incoming));

        assertEquals("Choose a non-empty file no larger than 32 bytes.",failure.getMessage());
        verify(incoming,never()).getInputStream();
        verify(documents,never()).findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(anyLong(),any(org.springframework.data.domain.Pageable.class));
        verify(documents,never()).saveAndFlush(any());
        verifyNoInteractions(quota,scanner,storage);
    }

    @Test void dishonestDeclaredSizeCannotReadPastConfiguredCapOrReachQuotaAdmissionScannerOrStorage() throws Exception {
        service=limited(new DocumentUploadLimits(32,8000000));
        var input=new ByteArrayInputStream(new byte[1000]);
        MultipartFile incoming=mock(MultipartFile.class);
        when(incoming.getSize()).thenReturn(1L);when(incoming.getInputStream()).thenReturn(input);

        assertThrows(IllegalArgumentException.class,()->service.upload(13L,"Passport",incoming));

        assertEquals(1000-33,input.available());
        verify(quota,never()).admit(any(),anyLong());
        verify(documents,never()).saveAndFlush(any());
        verify(rules,never()).transition(any(),any(),any());
        verifyNoInteractions(scanner,storage);
    }

    @Test void lowerConfiguredImagePixelCapRejectsFromDimensionsBeforeDecodingOrScanning() throws Exception {
        service=limited(new DocumentUploadLimits(3000000,8000000));
        // A header is enough to read dimensions. No image payload exists to decode.
        var incoming=new MockMultipartFile("file","large.png","image/png",pngHeader(4096,2048));

        var failure=assertThrows(IllegalArgumentException.class,()->service.upload(13L,"Passport",incoming));

        assertTrue(failure.getMessage().contains("8 megapixels"));
        verify(documents,never()).saveAndFlush(any());verifyNoInteractions(scanner,storage);
    }

    @Test void imageAtConfiguredPixelBoundaryReachesScannerAfterValidation() throws Exception {
        service=limited(new DocumentUploadLimits(1024,4));
        var encoded=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",encoded));
        byte[] image=encoded.toByteArray();
        var stopped=new IOException("Synthetic scanner stop.");
        doThrow(stopped).when(scanner).scan(any());

        assertSame(stopped,assertThrows(IOException.class,()->service.upload(13L,"Passport",
                new MockMultipartFile("file","small.png","image/png",image))));

        verify(scanner).scan(argThat(bytes->java.util.Arrays.equals(image,bytes)));
        verifyNoInteractions(storage);verify(documents,never()).saveAndFlush(any());
    }

    @Test void selectedFileCapAlsoRejectsOversizedDownloadWithUniformNotFoundBeforeScan() throws Exception {
        service=limited(new DocumentUploadLimits(32,8000000));
        when(documents.findById(1L)).thenReturn(Optional.of(stored(1,root.resolve("private-reference"))));
        when(storage.read(anyString())).thenReturn(new VisaDocumentStorage.Content(new byte[33],"pdf"));

        var failure=assertThrows(ResponseStatusException.class,()->service.download(1L));

        assertEquals(404,failure.getStatusCode().value());assertEquals("Document not found.",failure.getReason());
        verify(storage).read(anyString());verifyNoInteractions(scanner);
    }

    private VisaDocumentService limited(DocumentUploadLimits limits) {
        return new VisaDocumentService(documents,visas,rules,root.toString(),em,mock(VisaChecklistService.class),users,
                quota,scanner,storage,limits);
    }

    private static byte[] pngHeader(int width,int height) throws IOException {
        var headerBytes=new ByteArrayOutputStream();
        try(var header=new DataOutputStream(headerBytes)) {
            header.writeInt(width);header.writeInt(height);header.write(new byte[]{8,2,0,0,0});
        }
        byte[] type="IHDR".getBytes(StandardCharsets.US_ASCII),data=headerBytes.toByteArray();
        var checksum=new CRC32();checksum.update(type);checksum.update(data);
        var bytes=new ByteArrayOutputStream();
        try(var png=new DataOutputStream(bytes)) {
            png.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
            png.writeInt(data.length);png.write(type);png.write(data);png.writeInt((int)checksum.getValue());
        }
        return bytes.toByteArray();
    }
}
