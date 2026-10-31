package com.travelgo.service;
import com.travelgo.dto.BookingRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class BookingService {
    private final BookingRepository repository;
    private final TourPackageRepository packages;
    private final HotelRepository hotels;
    private final BookingHotelRepository bookingHotels;
    private final TravelerRepository travelers;
    private final WorkflowRules rules;
    private final Validator validator;
    private final UserRepository users; private final BookingEmailService email;
    private final DepartureRepository departures; private final SupplierHoldRepository supplierHolds;
    private final StaffUpdatePublisher staffUpdates;
    private final BookingNoteRepository bookingNotes;
    private final AuditService auditService;
    private final BookingStatusHistoryRepository historyRepo;
    private final TravelPolicyService policies;
    private final SupplierReservationService supplierReservations;
    private final CancellationRequestRepository cancellationRequests;
    private final VisaChecklistService checklists;
    private final BookingSummaryService summaries;
    private final RefundRepository refunds;
    // Directly constructed compatibility fixtures keep the opt-in policy disabled.
    @org.springframework.beans.factory.annotation.Autowired
    private BookingHoldPolicy pendingHolds = BookingHoldPolicy.disabled();
    public BookingService(BookingRepository repository, TourPackageRepository packages, HotelRepository hotels,
            BookingHotelRepository bookingHotels, TravelerRepository travelers, WorkflowRules rules, Validator validator, UserRepository users, BookingEmailService email, DepartureRepository departures, SupplierHoldRepository supplierHolds, StaffUpdatePublisher staffUpdates, BookingNoteRepository bookingNotes, AuditService auditService, BookingStatusHistoryRepository historyRepo, TravelPolicyService policies, SupplierReservationService supplierReservations, CancellationRequestRepository cancellationRequests,VisaChecklistService checklists, BookingSummaryService summaries, RefundRepository refunds) {
        this.repository=repository; this.packages=packages; this.hotels=hotels; this.bookingHotels=bookingHotels;
        this.travelers=travelers; this.rules=rules; this.validator=validator;this.users=users;this.email=email;
        this.departures=departures; this.supplierHolds=supplierHolds;
        this.staffUpdates=staffUpdates; this.bookingNotes=bookingNotes; this.auditService=auditService; this.historyRepo=historyRepo;
        this.policies=policies;this.supplierReservations=supplierReservations;
        this.cancellationRequests=cancellationRequests;
        this.checklists=checklists;
        this.summaries=summaries;
        this.refunds=refunds;
    }
    private void changeStatus(Booking b, BookingStatus newStatus, String remarks) {
        if (b.getBookingStatus() != newStatus || b.getId() == null) {
            b.setBookingStatus(newStatus);
            if (b.getId() == null) repository.save(b);
            BookingStatusHistory h = new BookingStatusHistory();
            h.setBooking(b);
            h.setStatus(newStatus);
            h.setRemarks(remarks);
            try { h.setChangedBy(rules.actor()); } catch (Exception e) { h.setChangedBy(b.getUser()); }
            historyRepo.save(h);
        }
    }
    public List<Booking> findAll() { return repository.findAll(); }
    public Optional<Booking> findById(Long id) { return repository.findById(id); }
    public List<Booking> findByUserId(Long id) { return repository.findByUser_IdOrderByCreatedAtDesc(id); }
    public boolean canCustomerEdit(Booking b) { return rules.editable(b); }
    private void validate(TourPackage p, LocalDate date, Integer count) {
        if (!p.isActive() || !p.getDestination().isActive()) throw new IllegalArgumentException("This tour package is unavailable. Choose another package or contact the agency.");
        if (count == null || count < 1 || p.getMaxCapacity() == null || count > p.getMaxCapacity()) throw new IllegalArgumentException("Choose at least one traveler and stay within the package capacity.");
        if (date == null || !date.atStartOfDay().minusDays(3).isAfter(rules.now())) throw new IllegalArgumentException("Select a travel date more than three days ahead.");
        if (p.getDurationDays() == null || p.getDurationDays() < 1) throw new IllegalArgumentException("This package needs a duration review. Contact the agency before booking.");
    }

    private Departure scheduledDeparture(TourPackage tourPackage, LocalDate date) {
        List<Departure> matches = departures.findByTourPackage_Id(tourPackage.getId()).stream()
                .filter(departure -> date.equals(departure.getDepartureDate()))
                .toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("No departure is scheduled for this date. Choose an available date.");
        if (matches.size() > 1) throw new IllegalStateException("More than one departure is listed for this date. Ask the agency to review the schedule.");
        return matches.get(0);
    }

    /** Locks in a stable id order to avoid cross-date editing deadlocks. */
    private Departure[] lockDepartures(Departure current, Departure target) {
        if (current.getId().equals(target.getId())) {
            Departure locked = departures.findByIdForUpdate(current.getId())
                    .orElseThrow(() -> new IllegalStateException("The scheduled departure is unavailable."));
            return new Departure[]{locked, locked};
        }
        boolean currentFirst = current.getId() < target.getId();
        Departure first = departures.findByIdForUpdate(currentFirst ? current.getId() : target.getId())
                .orElseThrow(() -> new IllegalStateException("The scheduled departure is unavailable."));
        Departure second = departures.findByIdForUpdate(currentFirst ? target.getId() : current.getId())
                .orElseThrow(() -> new IllegalStateException("The scheduled departure is unavailable."));
        return currentFirst ? new Departure[]{first, second} : new Departure[]{second, first};
    }

    private void reserve(Departure departure, int travelers) {
        if (travelers <= 0) return;
        Integer available = departure.getAvailableSeats();
        Integer total = departure.getTotalCapacity();
        if (available == null || total == null || available < 0 || available > total) {
            throw new IllegalStateException("Seat availability needs an agency review before this booking can change.");
        }
        requireReconciledInventory(departure);
        if (available < travelers) {
            throw new IllegalArgumentException("Not enough seats remain on this departure. Only " + available + " seats are available.");
        }
        departure.setAvailableSeats(available - travelers);
    }

    private void release(Departure departure, int travelers) {
        if (travelers <= 0) return;
        Integer available = departure.getAvailableSeats();
        Integer total = departure.getTotalCapacity();
        if (available == null || total == null || available < 0 || available > total || available + travelers > total) {
            throw new IllegalStateException("Seat availability needs an agency review before this booking can change.");
        }
        requireReconciledInventory(departure);
        departure.setAvailableSeats(available + travelers);
    }

    private void requireReconciledInventory(Departure departure) {
        Integer allocated = repository.sumTravelersForPackageAndDate(departure.getTourPackage().getId(), departure.getDepartureDate());
        if (allocated != null && allocated > departure.getTotalCapacity() - departure.getAvailableSeats()) {
            throw new IllegalStateException("Seat availability does not match existing bookings. Review the allocations before changing this departure.");
        }
    }

    private void releaseBookingSeats(Booking booking) {
        if (booking.getDeparture() == null || booking.getNumberOfTravelers() == null) {
            throw new IllegalStateException("This booking needs a departure allocation review. Contact your travel consultant.");
        }
        Departure departure = departures.findByIdForUpdate(booking.getDeparture().getId())
                .orElseThrow(() -> new IllegalStateException("The scheduled departure is unavailable."));
        release(departure, booking.getNumberOfTravelers());
        departures.save(departure);
    }
    private void details(List<BookingRequest.TravelerInput> list, int count) {
        if (list == null || list.size() != count || list.stream().anyMatch(t -> t == null || !validator.validate(t).isEmpty() || !t.dob().isBefore(rules.now().toLocalDate())))
            throw new IllegalArgumentException("Enter complete details for every traveler, including a date of birth before today.");
    }
    private void saveTravelers(Booking b, List<BookingRequest.TravelerInput> list) {
        for (var input : list) {
            Traveler t = new Traveler(); t.setBooking(b); t.setFullName(input.name().trim()); t.setPassportNumber(input.passport().trim());
            t.setDateOfBirth(input.dob()); t.setGender(input.gender().trim()); t.setNationality(input.nationality().trim()); b.getTravelers().add(t); travelers.save(t);
        }
    }
    private void hotel(Booking b, BookingHotel bh) {
        Hotel h = bh.getHotel();
        if (!h.isActive() || !h.getDestination().getId().equals(b.getTourPackage().getDestination().getId()))
            throw new IllegalArgumentException("Choose an available hotel in your tour destination.");
        rules.money(h.getPricePerNight(), false);
        int rooms = (b.getNumberOfTravelers()+1)/2; int nights = Math.max(1, b.getTourPackage().getDurationDays()-1);
        bh.setCheckInDate(b.getTravelDate()); bh.setCheckOutDate(b.getTravelDate().plusDays(nights));
        bh.setNumberOfRooms(rooms); bh.setNumberOfNights(nights);
        bh.setHotelCost(h.getPricePerNight().multiply(BigDecimal.valueOf(rooms)).multiply(BigDecimal.valueOf(nights)));
        rules.money(bh.getHotelCost(), false); bookingHotels.save(bh);
    }
    public Booking createBooking(BookingRequest request) {return createBooking(request, java.util.UUID.randomUUID().toString());}
    public Booking createBooking(BookingRequest request,String requestToken) {
        return createBooking(request,requestToken,false,null);
    }
    public Booking createBooking(BookingRequest request,String requestToken,Boolean acceptedPolicy,Long policyId) {
        rules.requireRole("CUSTOMER");
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("Complete the required booking fields before submitting.");
        if(requestToken==null||!requestToken.matches("[a-zA-Z0-9-]{16,64}"))throw new IllegalArgumentException("Refresh the booking form before submitting.");
        User owner=users.lockAccount(rules.actor().getId()).orElseThrow();
        String key=owner.getId()+":"+requestToken;
        String fingerprint;
        try {fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var previous=repository.findByRequestKey(key);
        if(previous.isPresent()){if(!fingerprint.equals(previous.get().getRequestFingerprint()))throw new IllegalStateException("This request was already submitted with different details. Start a new booking.");return previous.get();}
        pendingHolds.requireAdmission(owner.getId(), repository);
        TourPackage p = packages.findForUpdate(request.packageId()).orElseThrow(() -> new IllegalArgumentException("Package not found."));
        validate(p, request.travelDate(), request.numberOfTravelers()); details(request.travelers(), request.numberOfTravelers());
        Departure requestedDeparture = scheduledDeparture(p, request.travelDate());
        Departure departure = lockDepartures(requestedDeparture, requestedDeparture)[0];
        reserve(departure, request.numberOfTravelers());
        departures.save(departure);

        rules.money(p.getBasePrice(), false);
        Booking b = new Booking(); b.setUser(owner);b.setRequestKey(key);b.setRequestFingerprint(fingerprint); b.setTourPackage(p); b.setTravelDate(request.travelDate());
        policies.validateAcceptance(b,acceptedPolicy,policyId);
        b.setDeparture(departure);
        b.setNumberOfTravelers(request.numberOfTravelers()); b.setPackageUnitPrice(p.getBasePrice());
        b.setTotalPackageAmount(p.getBasePrice().multiply(BigDecimal.valueOf(request.numberOfTravelers())));
        rules.money(b.getTotalPackageAmount(), false); changeStatus(b, BookingStatus.PENDING, "Booking created");
        b.setPackagePaymentDeadline(b.getTravelDate().atStartOfDay().minusDays(3)); repository.saveAndFlush(b);
        
        SupplierHold hold = new SupplierHold();
        hold.setBooking(b);
        hold.setSupplierReference(pendingHolds.newReference());
        hold.setHoldExpiresAt(rules.now().plus(pendingHolds.duration()));
        hold.setConfirmed(false);
        supplierHolds.save(hold);

        saveTravelers(b, request.travelers());
        if (request.hotelId() != null) {
            BookingHotel bh = new BookingHotel(); bh.setBooking(b);
            bh.setHotel(hotels.findById(request.hotelId()).orElseThrow(() -> new IllegalArgumentException("Hotel not found."))); hotel(b, bh);
        }
        email.booking(b,false);
        rules.bookingNotice(b, "BOOKING_CREATED", "Booking request created", "Booking #" + b.getId() + " was saved. "
                + (pendingHolds.enabled() ? "Start your visa application within " + pendingHolds.minutes()
                + " minutes to keep the allocated seats. An unstarted request expires automatically." : "Submit your visa documents to continue."));
        notifyConsultants("NEW_BOOKING", "New Booking Received", "Booking #" + b.getId() + " requires review.", b.getId());
        return b;
    }

    private void notifyConsultants(String eventType, String title, String message, Long bookingId) {
        users.findAll().stream()
                .filter(u -> u.isActive() && rules.role(u, "TRAVEL_CONSULTANT"))
                .forEach(u -> rules.notify(u, eventType, title, message, "BOOKING", bookingId));
        staffUpdates.bookingChanged(eventType, bookingId);
    }
    public Booking modifyBooking(Long id, LocalDate date, Integer count, List<BookingRequest.TravelerInput> replacement, LocalDateTime expectedUpdatedAt) {
        Booking b=rules.lock(id); rules.owner(b); rules.requireEditable(b);
        if (expectedUpdatedAt == null || !expectedUpdatedAt.equals(b.getUpdatedAt())) throw new IllegalStateException("This booking has changed. Refresh the page before editing.");
        LocalDate nextDate=date == null ? b.getTravelDate() : date; int nextCount=count == null ? b.getNumberOfTravelers() : count;
        validate(b.getTourPackage(), nextDate, nextCount);
        if (b.getDeparture() == null) throw new IllegalStateException("This booking needs a departure allocation review. Contact your travel consultant.");
        Departure requestedDeparture = scheduledDeparture(b.getTourPackage(), nextDate);
        Departure[] locked = lockDepartures(b.getDeparture(), requestedDeparture);
        Departure currentDeparture = locked[0];
        Departure nextDeparture = locked[1];
        if (currentDeparture.getId().equals(nextDeparture.getId())) {
            int difference = nextCount - b.getNumberOfTravelers();
            if (difference > 0) reserve(currentDeparture, difference); else release(currentDeparture, -difference);
            departures.save(currentDeparture);
        } else {
            release(currentDeparture, b.getNumberOfTravelers());
            reserve(nextDeparture, nextCount);
            departures.save(currentDeparture);
            departures.save(nextDeparture);
        }
        if (nextCount != b.getNumberOfTravelers() || replacement != null) {
            details(replacement, nextCount); b.getTravelers().clear(); travelers.flush(); saveTravelers(b, replacement);
        }
        b.setTravelDate(nextDate); b.setDeparture(nextDeparture); b.setNumberOfTravelers(nextCount);
        try {var visa=rules.visa(b);if(visa.getChecklist()!=null)checklists.checkLeadTime(b,visa.getChecklist());}
        catch(IllegalStateException absent){if(!"A visa application is required.".equals(absent.getMessage()))throw absent;}
        b.setTotalPackageAmount(b.getPackageUnitPrice().multiply(BigDecimal.valueOf(nextCount))); rules.money(b.getTotalPackageAmount(), false);
        b.setPackagePaymentDeadline(nextDate.atStartOfDay().minusDays(3)); bookingHotels.findByBooking_Id(id).ifPresent(bh -> hotel(b,bh));
        rules.bookingNotice(b, "BOOKING_UPDATED", "Booking updated", "Booking #" + b.getId() + " travel details were updated.");
        notifyConsultants("BOOKING_UPDATED", "Booking updated", "Booking #" + b.getId() + " travel details were updated by customer.", b.getId());
        return repository.save(b);
    }
    public void cancelBooking(Long id) { Booking b=rules.lock(id); rules.owner(b); rules.requireEditable(b); releaseBookingSeats(b); changeStatus(b, BookingStatus.CANCELLED, "Customer cancelled booking"); email.booking(b,true); rules.bookingNotice(b, "BOOKING_CANCELLED", "Booking cancelled", "Booking #" + b.getId() + " was cancelled."); notifyConsultants("BOOKING_CANCELLED", "Booking cancelled", "Booking #" + b.getId() + " was cancelled by customer.", b.getId()); auditService.logAction("CANCEL_BOOKING", "Booking", b.getId(), "Booking was cancelled."); }
    public void cancelAcceptedRequest(Long id,Long requestId){
        Booking b=rules.lock(id);rules.owner(b);
        var request=cancellationRequests.findById(requestId).orElseThrow(()->new IllegalArgumentException("Cancellation request not found."));
        if(!request.getBooking().getId().equals(id)||!"ACCEPTED".equals(request.getStatus())||request.getAcceptedAt()==null)
            throw new IllegalStateException("An accepted customer cancellation quote is required.");
        if(b.getBookingStatus()==BookingStatus.CANCELLED)return;
        releaseBookingSeats(b);changeStatus(b,BookingStatus.CANCELLED,"Customer accepted cancellation quote #"+requestId);
        email.booking(b,true);rules.bookingNotice(b,"BOOKING_CANCELLED","Booking cancelled","Booking #"+id+" was cancelled after you accepted the quote. Any agreed refunds remain pending settlement.");
        notifyConsultants("BOOKING_CANCELLED","Booking cancelled","Accepted cancellation quote #"+requestId+" cancelled booking #"+id+".",id);
    }
    public boolean canConfirm(Booking b) {
        try {
            if (!supplierReservations.ready(b)) return false;
            rules.eligible(b);
            if (rules.visa(b).getStatus() != VisaStatus.APPROVED) return false;
            rules.requirePaid(b, PaymentType.VISA_DOCUMENTATION);
            rules.requirePaid(b, PaymentType.FULL_PACKAGE);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) { return false; }
    }
    public void confirmBooking(Long id) {
        rules.requireRole("TRAVEL_CONSULTANT"); Booking b=rules.lock(id);
        supplierReservations.requireReady(b);
        if (b.getBookingStatus() != BookingStatus.CONFIRMED) rules.eligible(b);
        if (rules.visa(b).getStatus() != VisaStatus.APPROVED) throw new IllegalStateException("The visa must be approved before this booking can be confirmed.");
        rules.requirePaid(b, PaymentType.VISA_DOCUMENTATION);
        rules.requirePaid(b, PaymentType.FULL_PACKAGE);
        if (b.getBookingStatus() == BookingStatus.CONFIRMED) return;
        changeStatus(b, BookingStatus.CONFIRMED, "All payments received. Booking confirmed.");
        auditService.logAction("CONFIRM_BOOKING", "Booking", b.getId(), "Booking was confirmed by staff.");
        rules.bookingNotice(b, "BOOKING_CONFIRMED", "Booking confirmed", "Booking #" + b.getId() + " is confirmed. Your trip is scheduled.");
        staffUpdates.bookingChanged("BOOKING_CONFIRMED", b.getId());
    }

    /** Availability is advisory; completeBooking rechecks every condition after acquiring the booking lock. */
    public boolean canComplete(Booking booking) {
        try { requireCompletionEligible(booking); return true; }
        catch (IllegalArgumentException | IllegalStateException e) { return false; }
    }

    public LocalDate scheduledEndDate(Booking booking) {
        return BookingSchedule.endDate(booking);
    }

    private void requireCompletionEligible(Booking booking) {
        if (booking.getBookingStatus() != BookingStatus.CONFIRMED)
            throw new IllegalStateException("Only a confirmed booking can be marked complete.");
        if (!rules.now().toLocalDate().isAfter(scheduledEndDate(booking)))
            throw new IllegalStateException("The trip can be marked complete only after its scheduled end date.");
        supplierReservations.requireReady(booking);
        if (rules.visa(booking).getStatus() != VisaStatus.APPROVED)
            throw new IllegalStateException("Review the visa approval before marking this booking complete.");
        rules.requirePaid(booking, PaymentType.VISA_DOCUMENTATION);
        rules.requirePaid(booking, PaymentType.FULL_PACKAGE);
        for (PaymentType type : List.of(PaymentType.VISA_DOCUMENTATION, PaymentType.FULL_PACKAGE))
            if (rules.successful(booking, type).filter(payment -> refunds.findByPayment_Id(payment.getId()).isPresent()).isPresent())
                throw new IllegalStateException("Review the pending or processed refund before marking this booking complete.");
        if (summaries.forBooking(booking).reviewRequired())
            throw new IllegalStateException("Review the payments and refunds before marking this booking complete.");
        if (cancellationRequests.findByBooking_IdOrderByIdDesc(booking.getId()).stream()
                .anyMatch(request -> List.of("REQUESTED", "QUOTED").contains(request.getStatus())))
            throw new IllegalStateException("Resolve the open cancellation request before marking this booking complete.");
    }

    /** Explicit staff closeout, never an automatic consequence of time passing. */
    public void completeBooking(Long id) {
        rules.requireRole("TRAVEL_CONSULTANT");
        User actor = rules.actor();
        if (actor.getRole().getPermissions().stream()
                .noneMatch(permission -> "BOOKING_MANAGE".equals(permission.getPermissionName())))
            throw new org.springframework.security.access.AccessDeniedException("Booking management permission is required.");
        Booking booking = rules.lock(id);
        if (booking.getBookingStatus() == BookingStatus.COMPLETED) return;
        requireCompletionEligible(booking);
        changeStatus(booking, BookingStatus.COMPLETED, "Trip completed after its scheduled end date. Visa, payments and supplier requirements checked.");
        auditService.logAction("COMPLETE_BOOKING", "Booking", id, "Trip marked complete by staff after the scheduled end date.");
        rules.bookingNotice(booking, "BOOKING_COMPLETED", "Trip completed",
                "Your trip for booking #" + id + " has been marked complete. You can share your feedback in the booking details.");
        email.completed(booking);
        staffUpdates.bookingChanged("BOOKING_COMPLETED", id);
    }

    /** Consultant declines a booking with a recorded reason. */
    public void declineBooking(Long id, String reason) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (reason == null || reason.isBlank() || reason.length() > 2000)
            throw new IllegalArgumentException("Provide a decline reason (up to 2,000 characters).");
        Booking b = rules.lock(id);
        if (b.getBookingStatus() == BookingStatus.DECLINED) return; // idempotent
        if (!rules.editable(b))
            throw new IllegalStateException("A booking can be declined only before visa review or payment begins.");
        releaseBookingSeats(b);
        changeStatus(b, BookingStatus.DECLINED, reason);
        b.setDeclineReason(reason.trim());
        auditService.logAction("DECLINE_BOOKING", "Booking", b.getId(), "Booking was declined. Reason: " + reason);
        b.setDeclinedAt(rules.now());
        b.setDeclinedBy(rules.actor());
        rules.bookingNotice(b, "BOOKING_DECLINED", "Booking declined",
                "Booking #" + b.getId() + " was declined by staff. Reason: " + reason.trim());
        staffUpdates.bookingChanged("BOOKING_DECLINED", b.getId());
    }

    /** Consultant requests more information from the customer. */
    public BookingNote requestInfo(Long bookingId, String message) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new IllegalArgumentException("Provide a message (up to 2,000 characters).");
        Booking b = rules.lock(bookingId);
        if (b.getBookingStatus() != BookingStatus.PENDING && b.getBookingStatus() != BookingStatus.PROCESSING)
            throw new IllegalStateException("Info can only be requested on pending or processing bookings.");
        changeStatus(b, BookingStatus.INFO_REQUIRED, message);
        BookingNote note = new BookingNote();
        note.setBooking(b);
        note.setAuthor(rules.actor());
        note.setNoteType("INFO_REQUEST");
        note.setMessage(message.trim());
        bookingNotes.save(note);
        rules.bookingNotice(b, "BOOKING_INFO_REQUESTED", "Information requested",
                "Staff needs more information about your booking #" + b.getId() + ": " + message.trim());
        staffUpdates.bookingChanged("BOOKING_INFO_REQUESTED", b.getId());
        return note;
    }

    /** Customer responds to a staff info request. */
    public BookingNote respondToInfoRequest(Long bookingId, String message) {
        rules.requireRole("CUSTOMER");
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new IllegalArgumentException("Provide a response (up to 2,000 characters).");
        Booking b = rules.lock(bookingId);
        rules.owner(b);
        if (b.getBookingStatus() != BookingStatus.INFO_REQUIRED)
            throw new IllegalStateException("No information request is pending for this booking.");
        changeStatus(b, BookingStatus.PENDING, "Customer provided requested information: " + message);
        BookingNote note = new BookingNote();
        note.setBooking(b);
        note.setAuthor(rules.actor());
        note.setNoteType("CUSTOMER_REPLY");
        note.setMessage(message.trim());
        bookingNotes.save(note);
        // Notify all active consultants
        users.findAll().stream()
                .filter(u -> u.isActive() && rules.role(u, "TRAVEL_CONSULTANT"))
                .forEach(u -> rules.notify(u, "BOOKING_INFO_REPLIED", "Customer replied to info request",
                        "Customer responded to booking #" + b.getId() + ". Review the updated information.",
                        "BOOKING", b.getId()));
        staffUpdates.bookingChanged("BOOKING_INFO_REPLIED", b.getId());
        return note;
    }

    /** Retrieve conversation notes for a booking. */
    public List<BookingNote> findNotes(Long bookingId) {
        return bookingNotes.findByBooking_IdOrderByCreatedAtAsc(bookingId);
    }
    public List<BookingStatusHistory> getStatusHistory(Long bookingId) {
        return historyRepo.findByBooking_IdOrderByChangedAtAsc(bookingId);
    }

    public void recordPaidConfirmation(Long id){
        Booking b=rules.lock(id);if(rules.role(rules.actor(),"CUSTOMER"))rules.owner(b);else rules.requireRole("VISA_OFFICER");
        if(b.getBookingStatus()!=BookingStatus.CONFIRMED && b.getBookingStatus()!=BookingStatus.COMPLETED)rules.eligible(b);
        supplierReservations.requireReady(b);
        if(rules.visa(b).getStatus()!=VisaStatus.APPROVED)throw new IllegalStateException("The visa must be approved before this booking can be confirmed.");
        rules.requirePaid(b,PaymentType.VISA_DOCUMENTATION);rules.requirePaid(b,PaymentType.FULL_PACKAGE);
        if(b.getBookingStatus()!=BookingStatus.CONFIRMED && b.getBookingStatus()!=BookingStatus.COMPLETED)changeStatus(b,BookingStatus.CONFIRMED,"Final payment recorded after visa approval.");
    }

    /** Used by the visa-rejection transaction after it has locked the same booking. */
    public void releaseSeatsForVisaRejection(Long bookingId) {
        Booking booking = rules.lock(bookingId);
        releaseBookingSeats(booking);
    }
}



