package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class VisaApplicationService {
    private final VisaApplicationRepository repository;
    private final VisaDocumentRepository documents;
    private final RefundService refunds;
    private final WorkflowRules rules;
    private final jakarta.persistence.EntityManager em;
    private final BookingEmailService emails;
    public VisaApplicationService(VisaApplicationRepository repository, VisaDocumentRepository documents,
            RefundService refunds, WorkflowRules rules, jakarta.persistence.EntityManager em, BookingEmailService emails) {
        this.repository = repository; this.documents = documents; this.refunds = refunds; this.rules = rules; this.em = em; this.emails = emails;
    }
    public List<VisaApplication> findAll() { return repository.findAll(); }
    public Optional<VisaApplication> findById(Long id) { return repository.findById(id); }
    public List<VisaApplication> findByUserId(Long id) { return repository.findByBooking_User_Id(id); }
    public Optional<VisaApplication> findByBookingId(Long id) { return repository.findByBooking_Id(id); }
    private VisaApplication locked(Long id) {
        VisaApplication v = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Visa application not found."));
        rules.lock(v.getBooking().getId()); em.refresh(v); return v;
    }
    public VisaApplication createVisaApplication(Booking request, VisaType type) {
        Booking b = rules.lock(request.getId()); rules.owner(b); rules.requireEditable(b);
        if (type == null) throw new IllegalArgumentException("Visa type is required.");
        Optional<VisaApplication> existing = repository.findByBooking_Id(b.getId());
        if (existing.isPresent()) return existing.get();
        VisaApplication v = new VisaApplication(); v.setBooking(b); v.setVisaType(type);
        rules.transition(v, VisaStatus.PENDING_DOCUMENTS, "Visa application created. Upload documents for review.");
        return v;
    }
    public VisaApplication markDocumentsVerified(Long id) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id); rules.requireDocumentsOpen(v);
        if (v.getStatus() == VisaStatus.DOCUMENTS_REQUIRED)
            throw new IllegalStateException("Wait for the customer to upload the requested documents before verification.");
        var items = documents.findByVisaApplication_Id(id);
        if (items.isEmpty()) throw new IllegalStateException("Upload documents before verification.");
        items.forEach(d -> d.setVerified(true));
        rules.transition(v, VisaStatus.DOCUMENTS_VERIFIED, "The visa officer verified the submitted documents.");
        emails.visaUpdate(v, "The visa officer verified the submitted documents."); return v;
    }
    public VisaApplication requestDocuments(Long id, String reason) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id); rules.eligible(v.getBooking());
        if (reason == null || reason.isBlank() || reason.trim().length() > 2000)
            throw new IllegalArgumentException("Describe the required documents in 1 to 2000 characters.");
        if (!rules.documentsOpen(v) && v.getStatus() != VisaStatus.DOCUMENTS_VERIFIED)
            throw new IllegalStateException("Additional documents can be requested only before charges are issued.");
        String remarks = reason.trim();
        if (v.getStatus() == VisaStatus.DOCUMENTS_REQUIRED) {
            if (remarks.equals(v.getOfficerRemarks())) return v;
            throw new IllegalStateException("A document request is already pending. Wait for the customer's response.");
        }
        v.setOfficerRemarks(remarks);
        rules.transition(v, VisaStatus.DOCUMENTS_REQUIRED, "Additional documents required: " + remarks);
        emails.visaUpdate(v, "Additional documents required: " + remarks);
        return v;
    }
    public VisaApplication setVisaCharges(Long id, BigDecimal visaCharge, BigDecimal docCharge) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id); rules.eligible(v.getBooking());
        if (v.getStatus() != VisaStatus.DOCUMENTS_VERIFIED) throw new IllegalStateException("Verify documents before issuing charges. Existing quotes cannot be edited.");
        if (rules.successful(v.getBooking(), PaymentType.VISA_DOCUMENTATION).isPresent()) throw new IllegalStateException("Charges are already paid.");
        rules.money(visaCharge, true); rules.money(docCharge, true); rules.money(visaCharge.add(docCharge), false);
        v.setVisaCharge(visaCharge); v.setDocumentationCharge(docCharge); v.setPaymentExpiresAt(rules.now().plusHours(24));
        rules.transition(v, VisaStatus.PAYMENT_PENDING, "Visa charges issued. Complete upfront payment within 24 hours.");
        emails.visaUpdate(v, "Visa charges issued. Complete upfront payment within 24 hours."); return v;
    }
    public VisaApplication reissueCharges(Long id) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id); rules.eligible(v.getBooking());
        if ((v.getStatus() != VisaStatus.PAYMENT_PENDING && v.getStatus() != VisaStatus.EXPIRED)
                || v.getPaymentExpiresAt() == null || rules.now().isBefore(v.getPaymentExpiresAt()))
            throw new IllegalStateException("Only expired unpaid quotes can be reissued.");
        if (rules.successful(v.getBooking(), PaymentType.VISA_DOCUMENTATION).isPresent()) throw new IllegalStateException("Charges are already paid.");
        rules.money(v.getVisaCharge(), true); rules.money(v.getDocumentationCharge(), true); rules.money(v.getTotalCharge(), false);
        rules.transition(v, VisaStatus.EXPIRED, "The previous payment window expired.");
        v.setPaymentExpiresAt(rules.now().plusHours(24));
        rules.transition(v, VisaStatus.PAYMENT_PENDING, "Unchanged visa charges reissued for 24 hours.");
        emails.visaUpdate(v, "Unchanged visa charges reissued for 24 hours."); return v;
    }
    public VisaApplication startProcessing(Long id) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id); rules.eligible(v.getBooking());
        rules.requirePaid(v.getBooking(), PaymentType.VISA_DOCUMENTATION);
        if (v.getStatus() == VisaStatus.PROCESSING) return v;
        if (v.getStatus() != VisaStatus.PAYMENT_PENDING) throw new IllegalStateException("Visa is not awaiting processing.");
        if (documents.findByVisaApplication_Id(id).isEmpty() || documents.findByVisaApplication_Id(id).stream().anyMatch(d -> !d.isVerified()))
            throw new IllegalStateException("Documents must be verified first.");
        rules.transition(v, VisaStatus.PROCESSING, "Visa processing started.");
        emails.visaUpdate(v, "Visa processing started."); return v;
    }
    public VisaApplication approveVisa(Long id) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id); rules.eligible(v.getBooking());
        if (v.getStatus() == VisaStatus.APPROVED) return v;
        if (v.getStatus() != VisaStatus.PROCESSING) throw new IllegalStateException("Only processing applications can be approved.");
        rules.requirePaid(v.getBooking(), PaymentType.VISA_DOCUMENTATION);
        rules.transition(v, VisaStatus.APPROVED, "Visa approved. Complete the final package payment before its deadline.");
        emails.visaUpdate(v, "Visa approved. Complete the final package payment before its deadline."); return v;
    }
    public VisaApplication rejectVisa(Long id, String reason, Payment ignored) {
        rules.requireRole("VISA_OFFICER"); VisaApplication v = locked(id);
        if (v.getStatus() == VisaStatus.REJECTED) { refunds.simulateVisaRejectionRefund(v, null); return v; }
        rules.eligible(v.getBooking());
        if (v.getStatus() != VisaStatus.PROCESSING) throw new IllegalStateException("Only processing applications can be rejected.");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A rejection reason is required.");
        rules.requirePaid(v.getBooking(), PaymentType.VISA_DOCUMENTATION);
        if (rules.successful(v.getBooking(), PaymentType.FULL_PACKAGE).isPresent()) throw new IllegalStateException("Unexpected final payment. Staff reconciliation required.");
        v.setRejectionReason(reason.trim()); v.getBooking().setBookingStatus(BookingStatus.CANCELLED);
        rules.transition(v, VisaStatus.REJECTED, "Visa rejected: " + reason.trim());
        emails.visaUpdate(v, "Visa rejected: " + reason.trim());
        refunds.simulateVisaRejectionRefund(v, null); return v;
    }
}
