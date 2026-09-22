package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Shared invariants. Call lock before reading mutable workflow state in a transaction. */
@Component
public class WorkflowRules {
    private final BookingRepository bookings;
    private final UserRepository users;
    private final VisaApplicationRepository visas;
    private final PaymentRepository payments;
    private final BookingHotelRepository hotels;
    private final VisaStatusHistoryRepository history;
    private final NotificationRepository notifications;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    private final VisaDocumentRepository documents;

    public WorkflowRules(BookingRepository bookings, UserRepository users, VisaApplicationRepository visas,
            PaymentRepository payments, BookingHotelRepository hotels, VisaStatusHistoryRepository history,
            NotificationRepository notifications, Clock clock, VisaDocumentRepository documents) {
        this.bookings = bookings; this.users = users; this.visas = visas; this.payments = payments;
        this.hotels = hotels; this.history = history; this.notifications = notifications; this.clock = clock; this.documents = documents;
    }
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public User actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Sign in required.");
        return users.findByEmail(auth.getName()).filter(User::isActive)
                .orElseThrow(() -> new AccessDeniedException("Active account required."));
    }
    public boolean role(User user, String role) { return com.travelgo.security.RoleNames.canonical(user.getRole().getRoleName()).equals(role); }
    public void requireRole(String role) {
        if (!role(actor(), role)) throw new AccessDeniedException("Access denied.");
    }
    public void owner(Booking b) {
        User user = actor();
        if (!role(user, "CUSTOMER") || !b.getUser().getId().equals(user.getId()))
            throw new AccessDeniedException("Access denied.");
    }
    public Booking lock(Long id) {
        Booking b = bookings.findById(id).orElseThrow(() -> new IllegalArgumentException("Booking not found."));
        if (em.getLockMode(b) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            em.refresh(b, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return b;
    }
    public VisaApplication visa(Booking b) {
        return visas.findByBooking_Id(b.getId()).orElseThrow(() -> new IllegalStateException("A visa application is required."));
    }
    public void eligible(Booking b) {
        if (b.getBookingStatus() != BookingStatus.PENDING && b.getBookingStatus() != BookingStatus.PROCESSING
                && b.getBookingStatus() != BookingStatus.INFO_REQUIRED)
            throw new IllegalStateException("This booking cannot continue in its current state.");
    }
    public boolean editable(Booking b) {
        if (b.getBookingStatus() != BookingStatus.PENDING || !payments.findByBooking_Id(b.getId()).isEmpty()) return false;
        return visas.findByBooking_Id(b.getId()).map(v -> documentsOpen(v)
            && v.getStatus() != VisaStatus.DOCUMENTS_REQUIRED
            && !history.existsByVisaApplication_IdAndStatus(v.getId(), VisaStatus.DOCUMENTS_REQUIRED)).orElse(true);
    }
    public void requireEditable(Booking b) {
        if (!editable(b)) throw new IllegalStateException("This reservation is locked because review or payment has begun. Contact staff.");
    }
    public boolean documentsOpen(VisaApplication v) {
        return v.getStatus() == VisaStatus.PENDING_DOCUMENTS || v.getStatus() == VisaStatus.DOCUMENTS_SUBMITTED
            || v.getStatus() == VisaStatus.DOCUMENTS_REQUIRED;
    }
    public void requireDocumentsOpen(VisaApplication v) {
        eligible(v.getBooking());
        if (!documentsOpen(v)) throw new IllegalStateException("Document review has already begun.");
    }
    public void requireVerifiedDocuments(VisaApplication v) {
        List<VisaDocument> items = documents.findByVisaApplication_Id(v.getId());
        if (items.isEmpty() || items.stream().anyMatch(d -> !d.isVerified()))
            throw new IllegalStateException("Verified documents are required. Staff review is needed.");
    }
    public void money(BigDecimal value, boolean zeroAllowed) {
        if (value == null || value.signum() < 0 || (!zeroAllowed && value.signum() == 0)
                || value.stripTrailingZeros().scale() > 2 || value.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new IllegalArgumentException("Enter a valid amount with at most two decimal places.");
    }
    public BigDecimal expected(Booking b, PaymentType type) {
        if (type == PaymentType.VISA_DOCUMENTATION) {
            BigDecimal amount = visa(b).getTotalCharge();
            money(amount, false); return amount;
        }
        BigDecimal totalPkg = b.getTotalPackageAmount().add(hotels.findByBooking_Id(b.getId()).map(BookingHotel::getHotelCost).orElse(BigDecimal.ZERO));
        BigDecimal amount;
        if (type == PaymentType.PACKAGE_DEPOSIT) {
            amount = totalPkg.multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_UP);
        } else if (type == PaymentType.PACKAGE_BALANCE) {
            amount = totalPkg.subtract(totalPkg.multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_UP));
        } else {
            amount = totalPkg;
        }
        money(amount, false); return amount;
    }
    public Optional<Payment> successful(Booking b, PaymentType type) {
        List<Payment> matches = payments.findByBooking_Id(b.getId()).stream()
            .filter(p -> p.getPaymentType() == type && (p.getPaymentStatus() == PaymentStatus.PAID || p.getPaymentStatus() == PaymentStatus.REFUNDED)).toList();
        if (matches.size() > 1) throw new IllegalStateException("Multiple successful payments exist. Staff must reconcile this booking.");
        return matches.stream().findFirst();
    }
    public void requirePaid(Booking b, PaymentType type) {
        Payment p = successful(b, type).orElseThrow(() -> new IllegalStateException("Required payment is missing."));
        if (p.getPaymentStatus() != PaymentStatus.PAID || p.getAmount().compareTo(expected(b, type)) != 0)
            throw new IllegalStateException("Payment does not match the booking. Staff review is required.");
    }
    public void transition(VisaApplication v, VisaStatus status, String message) {
        if (v.getStatus() == status) return;
        v.setStatus(status);
        if (role(actor(), "VISA_OFFICER")) v.setProcessedBy(actor());
        visas.save(v);
        VisaStatusHistory event = new VisaStatusHistory(); event.setVisaApplication(v); event.setStatus(status);
        event.setRemarks(message); event.setChangedBy(actor()); history.save(event);
        String title = switch (status) {
            case DOCUMENTS_REQUIRED -> "Additional documents required";
            case DOCUMENTS_SUBMITTED -> "Visa documents submitted";
            case APPROVED -> "Visa approved";
            case REJECTED -> "Visa rejected";
            case PAYMENT_PENDING -> "Visa payment required";
            default -> "Visa application update";
        };
        notify(v.getBooking().getUser(), "VISA_" + status.name(), title, message, "VISA", v.getId());
        if (status == VisaStatus.DOCUMENTS_SUBMITTED) {
            users.findAll().stream().filter(u -> u.isActive() && role(u, "VISA_OFFICER"))
                .forEach(u -> notify(u, "VISA_SUBMITTED", "Visa documents ready for review",
                    "Booking #" + v.getBooking().getId() + " has new documents to review.", "VISA", v.getId()));
        }
    }
    public void notify(User recipient, String type, String title, String message, String entityType, Long entityId) {
        Notification n = new Notification(); n.setUser(recipient); n.setTitle(title); n.setMessage(message);
        n.setType(type); n.setRelatedEntityType(entityType); n.setRelatedEntityId(entityId); n.setCreatedAt(now()); notifications.save(n);
    }
    public void bookingNotice(Booking b, String type, String title, String message) {
        notify(b.getUser(), type, title, message, "BOOKING", b.getId());
    }
}
