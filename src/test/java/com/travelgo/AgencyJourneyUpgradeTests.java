package com.travelgo;

import com.travelgo.enums.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the isolated inherited test profile; never the configured application database. */
class AgencyJourneyUpgradeTests extends WorkflowIntegrationTests {
    @Test void requestedDocumentsNotifyAndResubmissionKeepsBookingLocked() throws Exception {
        var b=create(true); var v=apply(b); documents.upload(v.getId(),"Initial document",pdf());
        login(officer); visas.requestDocuments(v.getId(),"Upload a clearer synthetic passport scan.");
        assertEquals(VisaStatus.DOCUMENTS_REQUIRED,visaRepo.findById(v.getId()).orElseThrow().getStatus());
        assertTrue(notificationRepo.findByUser_IdOrderByCreatedAtDesc(customer.getId()).stream()
            .anyMatch(n->"VISA_DOCUMENTS_REQUIRED".equals(n.getType())&&v.getId().equals(n.getRelatedEntityId())));
        assertThrows(IllegalStateException.class,()->visas.markDocumentsVerified(v.getId()));
        login(customer); assertFalse(bookings.canCustomerEdit(bookingRepo.findById(b.getId()).orElseThrow()));
        assertThrows(IllegalStateException.class,()->bookings.cancelBooking(b.getId()));
        documents.upload(v.getId(),"Clearer synthetic scan",pdf());
        assertFalse(bookings.canCustomerEdit(bookingRepo.findById(b.getId()).orElseThrow()));
        login(officer); visas.markDocumentsVerified(v.getId());
        assertEquals(VisaStatus.DOCUMENTS_VERIFIED,visaRepo.findById(v.getId()).orElseThrow().getStatus());
    }
    @Test void repeatedDocumentRequestDoesNotDuplicateNotification() throws Exception {
        var b=create(false); var v=apply(b); login(officer);
        visas.requestDocuments(v.getId(),"Upload test passport.");
        long count=notificationRepo.count(); long history=historyRepo.count();
        visas.requestDocuments(v.getId(),"Upload test passport.");
        assertEquals(count,notificationRepo.count());assertEquals(history,historyRepo.count());
        assertThrows(IllegalArgumentException.class,()->visas.requestDocuments(v.getId()," "));
    }
    @Test void additionalDocumentRequestCannotReopenIssuedFinancialQuote() throws Exception {
        var b=create(false); var v=quote(b); login(officer);
        assertThrows(IllegalStateException.class,()->visas.requestDocuments(v.getId(),"Extra document"));
        assertEquals(VisaStatus.PAYMENT_PENDING,visaRepo.findById(v.getId()).orElseThrow().getStatus());
    }
    @Test void customerVisaCreationDoesNotAssignCustomerAsProcessingOfficer() {
        var b=create(false); var v=apply(b);
        assertNull(visaRepo.findById(v.getId()).orElseThrow().getProcessedBy());
        assertTrue(historyRepo.findByVisaApplication_IdOrderByChangedAtAsc(v.getId()).stream().allMatch(h->customer.getId().equals(h.getChangedBy().getId())));
    }
}
