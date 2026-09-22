package com.travelgo.service;
import com.travelgo.dto.BookingRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final BookingNoteRepository bookingNotes;
    public BookingService(BookingRepository repository, TourPackageRepository packages, HotelRepository hotels,
            BookingHotelRepository bookingHotels, TravelerRepository travelers, WorkflowRules rules, Validator validator, UserRepository users, BookingEmailService email, DepartureRepository departures, SupplierHoldRepository supplierHolds, SimpMessagingTemplate messagingTemplate, BookingNoteRepository bookingNotes) {
        this.repository=repository; this.packages=packages; this.hotels=hotels; this.bookingHotels=bookingHotels;
        this.travelers=travelers; this.rules=rules; this.validator=validator;this.users=users;this.email=email;
        this.departures=departures; this.supplierHolds=supplierHolds;
        this.messagingTemplate=messagingTemplate; this.bookingNotes=bookingNotes;
    }
    public List<Booking> findAll() { return repository.findAll(); }
    public Optional<Booking> findById(Long id) { return repository.findById(id); }
    public List<Booking> findByUserId(Long id) { return repository.findByUser_IdOrderByCreatedAtDesc(id); }
    public boolean canCustomerEdit(Booking b) { return rules.editable(b); }
    private void validate(TourPackage p, LocalDate date, Integer count, Long excludeBookingId) {
        if (!p.isActive() || !p.getDestination().isActive()) throw new IllegalArgumentException("Package is not available.");
        if (count == null || count < 1 || p.getMaxCapacity() == null || count > p.getMaxCapacity()) throw new IllegalArgumentException("Traveler count exceeds the package limit.");
        
        Integer currentBooked = excludeBookingId == null ? 
            repository.sumTravelersForPackageAndDate(p.getId(), date) : 
            repository.sumTravelersForPackageAndDateExcluding(p.getId(), date, excludeBookingId);
        if (currentBooked == null) currentBooked = 0;
        
        if (currentBooked + count > p.getMaxCapacity()) {
            throw new IllegalArgumentException("Not enough capacity on this date. Only " + (p.getMaxCapacity() - currentBooked) + " seats left.");
        }

        if (date == null || !date.atStartOfDay().minusDays(3).isAfter(rules.now())) throw new IllegalArgumentException("Select a travel date more than three days ahead.");
        if (p.getDurationDays() == null || p.getDurationDays() < 1) throw new IllegalArgumentException("Package duration needs staff correction.");
    }
    private void details(List<BookingRequest.TravelerInput> list, int count) {
        if (list == null || list.size() != count || list.stream().anyMatch(t -> t == null || !validator.validate(t).isEmpty() || !t.dob().isBefore(rules.now().toLocalDate())))
            throw new IllegalArgumentException("Provide complete details for every traveler.");
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
            throw new IllegalArgumentException("Select an active hotel in the package destination.");
        rules.money(h.getPricePerNight(), false);
        int rooms = (b.getNumberOfTravelers()+1)/2; int nights = Math.max(1, b.getTourPackage().getDurationDays()-1);
        bh.setCheckInDate(b.getTravelDate()); bh.setCheckOutDate(b.getTravelDate().plusDays(nights));
        bh.setNumberOfRooms(rooms); bh.setNumberOfNights(nights);
        bh.setHotelCost(h.getPricePerNight().multiply(BigDecimal.valueOf(rooms)).multiply(BigDecimal.valueOf(nights)));
        rules.money(bh.getHotelCost(), false); bookingHotels.save(bh);
    }
    public Booking createBooking(BookingRequest request) {return createBooking(request, java.util.UUID.randomUUID().toString());}
    public Booking createBooking(BookingRequest request,String requestToken) {
        rules.requireRole("CUSTOMER");
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("Complete all booking fields.");
        if(requestToken==null||!requestToken.matches("[a-zA-Z0-9-]{16,64}"))throw new IllegalArgumentException("Refresh the booking form before submitting.");
        User owner=users.lockAccount(rules.actor().getId()).orElseThrow();
        String key=owner.getId()+":"+requestToken;
        String fingerprint;
        try {fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var previous=repository.findByRequestKey(key);
        if(previous.isPresent()){if(!fingerprint.equals(previous.get().getRequestFingerprint()))throw new IllegalStateException("This request was already submitted with different details. Start a new booking.");return previous.get();}
        TourPackage p = packages.findForUpdate(request.packageId()).orElseThrow(() -> new IllegalArgumentException("Package not found."));
        validate(p, request.travelDate(), request.numberOfTravelers(), null); details(request.travelers(), request.numberOfTravelers());
        
        Departure departure = departures.findByTourPackage_Id(p.getId()).stream()
            .filter(d -> d.getDepartureDate().equals(request.travelDate()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No scheduled departure for this date."));

        rules.money(p.getBasePrice(), false);
        Booking b = new Booking(); b.setUser(owner);b.setRequestKey(key);b.setRequestFingerprint(fingerprint); b.setTourPackage(p); b.setTravelDate(request.travelDate());
        b.setDeparture(departure);
        b.setNumberOfTravelers(request.numberOfTravelers()); b.setPackageUnitPrice(p.getBasePrice());
        b.setTotalPackageAmount(p.getBasePrice().multiply(BigDecimal.valueOf(request.numberOfTravelers())));
        rules.money(b.getTotalPackageAmount(), false); b.setBookingStatus(BookingStatus.PENDING);
        b.setPackagePaymentDeadline(b.getTravelDate().atStartOfDay().minusDays(3)); repository.saveAndFlush(b);
        
        SupplierHold hold = new SupplierHold();
        hold.setBooking(b);
        hold.setSupplierReference(java.util.UUID.randomUUID().toString());
        hold.setHoldExpiresAt(LocalDateTime.now().plusMinutes(15));
        hold.setConfirmed(false);
        supplierHolds.save(hold);

        saveTravelers(b, request.travelers());
        if (request.hotelId() != null) {
            BookingHotel bh = new BookingHotel(); bh.setBooking(b);
            bh.setHotel(hotels.findById(request.hotelId()).orElseThrow(() -> new IllegalArgumentException("Hotel not found."))); hotel(b, bh);
        }
        email.booking(b,false);
        rules.bookingNotice(b, "BOOKING_CREATED", "Booking request created", "Booking #" + b.getId() + " was saved. Submit your visa documents to continue.");
        notifyConsultants("NEW_BOOKING", "New Booking Received", "Booking #" + b.getId() + " requires review.", b.getId());
        return b;
    }

    private void notifyConsultants(String eventType, String title, String message, Long bookingId) {
        users.findAll().stream()
                .filter(u -> u.isActive() && rules.role(u, "TRAVEL_CONSULTANT"))
                .forEach(u -> rules.notify(u, eventType, title, message, "BOOKING", bookingId));
        messagingTemplate.convertAndSend("/topic/staff-updates", "{\"type\":\"" + eventType + "\",\"bookingId\":" + bookingId + "}");
    }
    public Booking modifyBooking(Long id, LocalDate date, Integer count, List<BookingRequest.TravelerInput> replacement, LocalDateTime expectedUpdatedAt) {
        Booking b=rules.lock(id); rules.owner(b); rules.requireEditable(b);
        if (expectedUpdatedAt == null || !expectedUpdatedAt.equals(b.getUpdatedAt())) throw new IllegalStateException("Reservation changed. Refresh the page before editing.");
        LocalDate nextDate=date == null ? b.getTravelDate() : date; int nextCount=count == null ? b.getNumberOfTravelers() : count;
        validate(b.getTourPackage(), nextDate, nextCount, id);
        if (nextCount != b.getNumberOfTravelers() || replacement != null) {
            details(replacement, nextCount); b.getTravelers().clear(); travelers.flush(); saveTravelers(b, replacement);
        }
        b.setTravelDate(nextDate); b.setNumberOfTravelers(nextCount);
        b.setTotalPackageAmount(b.getPackageUnitPrice().multiply(BigDecimal.valueOf(nextCount))); rules.money(b.getTotalPackageAmount(), false);
        b.setPackagePaymentDeadline(nextDate.atStartOfDay().minusDays(3)); bookingHotels.findByBooking_Id(id).ifPresent(bh -> hotel(b,bh));
        rules.bookingNotice(b, "BOOKING_UPDATED", "Booking updated", "Booking #" + b.getId() + " travel details were updated.");
        notifyConsultants("BOOKING_UPDATED", "Booking updated", "Booking #" + b.getId() + " travel details were updated by customer.", b.getId());
        return repository.save(b);
    }
    public void cancelBooking(Long id) { Booking b=rules.lock(id); rules.owner(b); rules.requireEditable(b); b.setBookingStatus(BookingStatus.CANCELLED); email.booking(b,true); rules.bookingNotice(b, "BOOKING_CANCELLED", "Booking cancelled", "Booking #" + b.getId() + " was cancelled."); notifyConsultants("BOOKING_CANCELLED", "Booking cancelled", "Booking #" + b.getId() + " was cancelled by customer.", b.getId()); }
    public boolean canConfirm(Booking b) {
        try {
            rules.eligible(b);
            if (rules.visa(b).getStatus() != VisaStatus.APPROVED) return false;
            rules.requirePaid(b, PaymentType.VISA_DOCUMENTATION);
            rules.requirePaid(b, PaymentType.PACKAGE_DEPOSIT);
            rules.requirePaid(b, PaymentType.PACKAGE_BALANCE);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) { return false; }
    }
    public void confirmBooking(Long id) {
        rules.requireRole("TRAVEL_CONSULTANT"); Booking b=rules.lock(id);
        if (b.getBookingStatus() != BookingStatus.CONFIRMED) rules.eligible(b);
        if (rules.visa(b).getStatus() != VisaStatus.APPROVED) throw new IllegalStateException("An approved visa is required.");
        rules.requirePaid(b, PaymentType.VISA_DOCUMENTATION);
        rules.requirePaid(b, PaymentType.PACKAGE_DEPOSIT);
        rules.requirePaid(b, PaymentType.PACKAGE_BALANCE);
        b.setBookingStatus(BookingStatus.CONFIRMED);
        rules.bookingNotice(b, "BOOKING_CONFIRMED", "Booking confirmed", "Booking #" + b.getId() + " is confirmed. Your trip is scheduled.");
        messagingTemplate.convertAndSend("/topic/staff-updates", "{\"type\":\"BOOKING_CONFIRMED\",\"bookingId\":" + b.getId() + "}");
    }

    /** Consultant declines a booking with a recorded reason. */
    public void declineBooking(Long id, String reason) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (reason == null || reason.isBlank() || reason.length() > 2000)
            throw new IllegalArgumentException("Provide a decline reason (up to 2,000 characters).");
        Booking b = rules.lock(id);
        if (b.getBookingStatus() == BookingStatus.DECLINED) return; // idempotent
        if (b.getBookingStatus() != BookingStatus.PENDING && b.getBookingStatus() != BookingStatus.PROCESSING
                && b.getBookingStatus() != BookingStatus.INFO_REQUIRED)
            throw new IllegalStateException("Only pending, processing, or info-required bookings can be declined.");
        b.setBookingStatus(BookingStatus.DECLINED);
        b.setDeclineReason(reason.trim());
        b.setDeclinedAt(rules.now());
        b.setDeclinedBy(rules.actor());
        rules.bookingNotice(b, "BOOKING_DECLINED", "Booking declined",
                "Booking #" + b.getId() + " was declined by staff. Reason: " + reason.trim());
        messagingTemplate.convertAndSend("/topic/staff-updates",
                "{\"type\":\"BOOKING_DECLINED\",\"bookingId\":" + b.getId() + "}");
    }

    /** Consultant requests more information from the customer. */
    public BookingNote requestInfo(Long bookingId, String message) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new IllegalArgumentException("Provide a message (up to 2,000 characters).");
        Booking b = rules.lock(bookingId);
        if (b.getBookingStatus() != BookingStatus.PENDING && b.getBookingStatus() != BookingStatus.PROCESSING)
            throw new IllegalStateException("Info can only be requested on pending or processing bookings.");
        b.setBookingStatus(BookingStatus.INFO_REQUIRED);
        BookingNote note = new BookingNote();
        note.setBooking(b);
        note.setAuthor(rules.actor());
        note.setNoteType("INFO_REQUEST");
        note.setMessage(message.trim());
        bookingNotes.save(note);
        rules.bookingNotice(b, "BOOKING_INFO_REQUESTED", "Information requested",
                "Staff needs more information about your booking #" + b.getId() + ": " + message.trim());
        messagingTemplate.convertAndSend("/topic/staff-updates",
                "{\"type\":\"BOOKING_INFO_REQUESTED\",\"bookingId\":" + b.getId() + "}");
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
        b.setBookingStatus(BookingStatus.PENDING);
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
        messagingTemplate.convertAndSend("/topic/staff-updates",
                "{\"type\":\"BOOKING_INFO_REPLIED\",\"bookingId\":" + b.getId() + "}");
        return note;
    }

    /** Retrieve conversation notes for a booking. */
    public List<BookingNote> findNotes(Long bookingId) {
        return bookingNotes.findByBooking_IdOrderByCreatedAtAsc(bookingId);
    }
}
