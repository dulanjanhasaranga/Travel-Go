package com.travelgo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Signed provider evidence requiring a human review; this is not a payment record. */
@Entity
@Table(name = "provider_payment_reviews")
public class ProviderPaymentReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 100) private String eventReference;
    @Column(length = 100) private String sessionReference;
    @Column(length = 100) private String paymentReference;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "booking_id") private Booking booking;
    @Column(precision = 19, scale = 2) private BigDecimal amount;
    @Column(length = 10) private String currency;
    @Column(nullable = false, length = 10) private String paymentMode;
    @Column(nullable = false, length = 400) private String reason;
    @Column(nullable = false, length = 20) private String status = "OPEN";
    @Column(nullable = false) private Instant createdAt;
    private Instant reviewedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reviewed_by_id") private User reviewedBy;
    @Column(length = 1000) private String reviewNote;
    public Long getId() { return id; }
    public String getEventReference() { return eventReference; } public void setEventReference(String value) { eventReference = value; }
    public String getSessionReference() { return sessionReference; } public void setSessionReference(String value) { sessionReference = value; }
    public String getPaymentReference() { return paymentReference; } public void setPaymentReference(String value) { paymentReference = value; }
    public Booking getBooking() { return booking; } public void setBooking(Booking value) { booking = value; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal value) { amount = value; }
    public String getCurrency() { return currency; } public void setCurrency(String value) { currency = value; }
    public String getPaymentMode() { return paymentMode; } public void setPaymentMode(String value) { paymentMode = value; }
    public String getReason() { return reason; } public void setReason(String value) { reason = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getReviewedAt() { return reviewedAt; } public void setReviewedAt(Instant value) { reviewedAt = value; }
    public User getReviewedBy() { return reviewedBy; } public void setReviewedBy(User value) { reviewedBy = value; }
    public String getReviewNote() { return reviewNote; } public void setReviewNote(String value) { reviewNote = value; }
}
