package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.enums.BookingStatus;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultant-entered supplier evidence. Internal allocation IDs are not supplier references. */
@Service("supplierReservations")
@Transactional(readOnly=true)
public class SupplierReservationService {
    private final SupplierHoldRepository holds;
    private final WorkflowRules rules;
    private final AuditService audit;
    private final boolean required;
    public SupplierReservationService(SupplierHoldRepository holds,WorkflowRules rules,AuditService audit,
          @Value("${travelgo.bookings.require-supplier-confirmation:false}") boolean required){this.holds=holds;this.rules=rules;this.audit=audit;this.required=required;}
    public boolean isRequired(){return required;}
    public Optional<SupplierHold> forBooking(Long id){return holds.findByBooking_Id(id);}
    public boolean confirmed(Booking b){return forBooking(b.getId()).filter(this::evidenceComplete).isPresent();}
    public boolean ready(Booking b){return !required||confirmed(b);}
    private boolean evidenceComplete(SupplierHold h){return h.hasConfirmationEvidence();}
    public void requireReady(Booking b){if(!ready(b))throw new IllegalStateException("Supplier confirmation is required before final payment and confirmation. Contact your consultant.");}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SupplierHold confirm(Long bookingId,Long expectedVersion,String supplier,String reference,String notes,boolean allServicesConfirmed){
        rules.requireRole("TRAVEL_CONSULTANT");Booking b=rules.lock(bookingId);
        if(b.getBookingStatus()==BookingStatus.CANCELLED||b.getBookingStatus()==BookingStatus.DECLINED||b.getBookingStatus()==BookingStatus.EXPIRED||b.getBookingStatus()==BookingStatus.COMPLETED)
            throw new IllegalStateException("Supplier confirmation is unavailable for a closed booking.");
        SupplierHold h=holds.findByBooking_Id(bookingId).orElseThrow(()->new IllegalStateException("This older booking has no supplier record. Review its allocation first."));
        supplier=text(supplier,200,"Supplier name");reference=text(reference,200,"Actual supplier confirmation reference");notes=text(notes,4000,"Confirmed services and evidence");
        if(evidenceComplete(h)&&Objects.equals(supplier,h.getSupplierName())&&Objects.equals(reference,h.getConfirmationReference())&&Objects.equals(notes,h.getServiceNotes()))return h;
        if(!Objects.equals(h.getVersion(),expectedVersion))throw new IllegalStateException("Supplier details changed. Refresh before saving.");
        if(evidenceComplete(h))throw new IllegalStateException("Supplier confirmation cannot be edited. Arrange changes with the supplier and add a booking note.");
        if(!allServicesConfirmed)throw new IllegalArgumentException("Confirm that suppliers have accepted every included service and the selected accommodation.");
        if(reference.equals(h.getSupplierReference()))throw new IllegalArgumentException("Enter the supplier's confirmation reference. The internal allocation reference is not accepted.");
        h.setSupplierName(supplier);h.setConfirmationReference(reference);h.setServiceNotes(notes);h.setConfirmed(true);h.setConfirmedAt(rules.now());h.setConfirmedBy(rules.actor());holds.saveAndFlush(h);
        audit.logAction("SUPPLIER_CONFIRMED","Booking",bookingId,"Supplier confirmation saved.");
        rules.bookingNotice(b,"SUPPLIER_CONFIRMED","Supplier confirmation recorded","Your consultant recorded supplier confirmation for booking #"+bookingId+". Visa and payment requirements still apply.");return h;
    }
    private String text(String value,int max,String label){if(value==null||value.trim().isEmpty()||value.trim().length()>max)throw new IllegalArgumentException(label+" is required (up to "+max+" characters).");return value.trim();}
}
