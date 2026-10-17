package com.travelgo.service;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Permission;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.entity.VisaApplication;
import com.travelgo.entity.VisaDocument;
import com.travelgo.enums.VisaStatus;
import com.travelgo.repository.UserRepository;
import com.travelgo.repository.VisaApplicationRepository;
import com.travelgo.repository.VisaDocumentRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Storage contract tests without a filesystem, network, transaction manager or Spring context. */
class VisaDocumentStorageServiceTests {
    private static final Long VISA_ID = 41L;
    private static final Long DOCUMENT_ID = 42L;
    private static final byte[] PDF = "%PDF-1.4\nSynthetic document fixture\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String REFERENCE = SupabaseDocumentReference.fresh(PDF,"pdf").encode();

    private final VisaDocumentRepository documents = mock(VisaDocumentRepository.class);
    private final VisaApplicationRepository visas = mock(VisaApplicationRepository.class);
    private final WorkflowRules rules = mock(WorkflowRules.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final VisaChecklistService checklists = mock(VisaChecklistService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final VisaDocumentScanner scanner = mock(VisaDocumentScanner.class);
    private final VisaDocumentStorage storage = mock(VisaDocumentStorage.class);
    private final VisaDocumentStorage.Cleanup cleanup = mock(VisaDocumentStorage.Cleanup.class);
    private final User owner = user(1L, "CUSTOMER");
    private final VisaApplication visa = visa(owner);
    private final VisaDocumentService service = new VisaDocumentService(documents, visas, rules,
            "unused-local-directory", entityManager, checklists, users,
            new DocumentUploadQuota(30, 120, 104857600L, 524288000L), scanner, storage);

    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void anotherCustomerIsDeniedBeforeStorageRead() {
        User otherCustomer = user(2L, "CUSTOMER");
        actor(otherCustomer);
        when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(retainedDocument()));

        assertNotFound(() -> service.download(DOCUMENT_ID));

        verifyNoInteractions(storage, scanner);
    }

    @Test void officerWithoutVisaPermissionIsDeniedBeforeDocumentLookupOrStorageRead() {
        User officer = user(3L, "VISA_OFFICER");
        officer.getRole().setPermissions(Set.of(new Permission("PAYMENT_VIEW", "Unrelated", "PAYMENT")));
        actor(officer);

        assertNotFound(() -> service.download(DOCUMENT_ID));

        verifyNoInteractions(documents, storage, scanner);
    }

    @Test void ownerReadsExactRetainedReferenceBeforeScanningDownloadedBytes() throws Exception {
        actor(owner);
        when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(retainedDocument()));
        when(storage.read(REFERENCE)).thenReturn(new VisaDocumentStorage.Content(PDF, "pdf"));

        var download = service.download(DOCUMENT_ID);

        assertArrayEquals(PDF, download.content());
        assertEquals("visa-document-42.pdf", download.filename());
        var order = inOrder(storage, scanner);
        order.verify(storage).read(REFERENCE);
        order.verify(scanner).scan(PDF);
        verifyNoMoreInteractions(storage, scanner);
    }

    @ParameterizedTest @ValueSource(strings = {"VISA_VIEW", "VISA_MANAGE"})
    void officerWithEitherVisaPermissionCanReadAnotherCustomersDocument(String permission) throws Exception {
        User officer = user(3L, "VISA_OFFICER");
        officer.getRole().setPermissions(Set.of(new Permission(permission, "Document access", "VISA")));
        actor(officer);
        when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(retainedDocument()));
        when(storage.read(REFERENCE)).thenReturn(new VisaDocumentStorage.Content(PDF, "pdf"));

        assertArrayEquals(PDF, service.download(DOCUMENT_ID).content());

        verify(storage).read(REFERENCE);
        verify(scanner).scan(PDF);
    }

    @Test void failedDownloadScanReturnsGenericNotFoundAfterReading() throws Exception {
        actor(owner);
        when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(retainedDocument()));
        when(storage.read(REFERENCE)).thenReturn(new VisaDocumentStorage.Content(PDF, "pdf"));
        doThrow(new IOException("Private scanner detail")).when(scanner).scan(PDF);

        var failure = assertNotFound(() -> service.download(DOCUMENT_ID));

        assertFalse(failure.toString().contains("Private scanner detail"));
        var order = inOrder(storage, scanner);
        order.verify(storage).read(REFERENCE);
        order.verify(scanner).scan(PDF);
    }

    @Test void uploadScansBeforeStoreAndPersistsExactServerReturnedReference() throws Exception {
        prepareUpload();
        TransactionSynchronizationManager.initSynchronization();

        var uploaded = service.upload(VISA_ID, " Passport ", file());

        var saved = ArgumentCaptor.forClass(VisaDocument.class);
        var order = inOrder(scanner, storage, documents);
        order.verify(scanner).scan(aryEq(PDF));
        order.verify(storage).store(aryEq(PDF), eq("pdf"));
        order.verify(documents).saveAndFlush(saved.capture());
        assertSame(uploaded, saved.getValue());
        assertEquals(REFERENCE, saved.getValue().getDocumentUrl());
        assertEquals("Passport", saved.getValue().getDocumentName());
        assertSame(visa, saved.getValue().getVisaApplication());
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(cleanup);
    }

    @Test void rejectedUploadScanNeverCreatesAnObjectOrPersistsADocument() throws Exception {
        prepareUpload();
        doThrow(new IOException("Scan rejected")).when(scanner).scan(any(byte[].class));

        assertThrows(IOException.class, () -> service.upload(VISA_ID, "Passport", file()));

        verifyNoInteractions(storage, cleanup);
        verify(documents, never()).saveAndFlush(any(VisaDocument.class));
    }

    @Test void rollbackCleansNewlyStoredObjectExactlyOnce() throws Exception {
        prepareUpload();
        TransactionSynchronizationManager.initSynchronization();
        service.upload(VISA_ID, "Passport", file());

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(cleanup, times(1)).run();
        verifyNoMoreInteractions(cleanup);
    }

    @Test void committedUploadPreservesNewlyStoredObject() throws Exception {
        prepareUpload();
        TransactionSynchronizationManager.initSynchronization();
        service.upload(VISA_ID, "Passport", file());

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verifyNoInteractions(cleanup);
    }

    @Test void databaseFailureUsesRegisteredRollbackCleanupExactlyOnce() throws Exception {
        prepareUpload();
        TransactionSynchronizationManager.initSynchronization();
        when(documents.saveAndFlush(any(VisaDocument.class))).thenThrow(new IllegalStateException("Write failed"));

        assertThrows(IllegalStateException.class, () -> service.upload(VISA_ID, "Passport", file()));
        verifyNoInteractions(cleanup);
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(cleanup, times(1)).run();
        verifyNoMoreInteractions(cleanup);
    }

    @Test void absentTransactionSynchronizationCleansCreatedObjectExactlyOnceBeforeFailing() throws Exception {
        prepareUpload();

        assertThrows(IllegalStateException.class, () -> service.upload(VISA_ID, "Passport", file()));

        verify(cleanup, times(1)).run();
        verifyNoMoreInteractions(cleanup);
        verify(documents, never()).saveAndFlush(any(VisaDocument.class));
    }

    @Test void retainedQuotaInspectionUsesSelectedStorageBeforeCreatingANewObject() throws Exception {
        prepareUpload();
        when(documents.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(eq(owner.getId()), any(Pageable.class)))
                .thenReturn(List.of(retainedDocument()));
        when(storage.retainedSize(REFERENCE)).thenReturn((long) PDF.length);
        TransactionSynchronizationManager.initSynchronization();

        service.upload(VISA_ID, "Additional document", file());

        var order = inOrder(storage);
        order.verify(storage).retainedSize(REFERENCE);
        order.verify(storage).store(aryEq(PDF), eq("pdf"));
        verify(storage, never()).read(anyString());
    }

    private void prepareUpload() throws IOException {
        actor(owner);
        when(users.lockAccount(owner.getId())).thenReturn(Optional.of(owner));
        when(visas.findById(VISA_ID)).thenReturn(Optional.of(visa));
        when(documents.findByVisaApplication_Booking_User_IdOrderByUploadedAtDescIdDesc(eq(owner.getId()), any(Pageable.class)))
                .thenReturn(List.of());
        when(storage.store(any(byte[].class), eq("pdf")))
                .thenReturn(new VisaDocumentStorage.StoredDocument(REFERENCE, cleanup));
    }

    private void actor(User actor) {
        when(rules.actor()).thenReturn(actor);
        when(rules.role(actor, "CUSTOMER")).thenReturn("CUSTOMER".equals(actor.getRole().getRoleName()));
        when(rules.role(actor, "VISA_OFFICER")).thenReturn("VISA_OFFICER".equals(actor.getRole().getRoleName()));
    }

    private VisaDocument retainedDocument() {
        var document = new VisaDocument();
        document.setId(DOCUMENT_ID);
        document.setVisaApplication(visa);
        document.setDocumentUrl(REFERENCE);
        return document;
    }

    private static User user(Long id, String role) {
        var user = new User("Synthetic user", "user-" + id + "@example.invalid", "unused", new Role(role, "Test role"));
        user.setId(id);
        return user;
    }

    private static VisaApplication visa(User owner) {
        var booking = new Booking();
        booking.setId(40L);
        booking.setUser(owner);
        var visa = new VisaApplication();
        visa.setId(VISA_ID);
        visa.setBooking(booking);
        visa.setStatus(VisaStatus.PENDING_DOCUMENTS);
        return visa;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "passport.pdf", "application/pdf", PDF);
    }

    private static ResponseStatusException assertNotFound(org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(ResponseStatusException.class, executable);
        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        assertEquals("Document not found.", failure.getReason());
        return failure;
    }

    private static void completeTransaction(int status) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.forEach(callback -> callback.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
    }
}
