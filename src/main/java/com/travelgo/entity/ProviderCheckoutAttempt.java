package com.travelgo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import com.travelgo.enums.PaymentType;

/** Fixes one hosted checkout's parameters before contacting the provider. */
@Entity @Table(name = "provider_checkout_attempts")
public class ProviderCheckoutAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "booking_id", nullable = false) private Booking booking;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PaymentType paymentType;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false) private LocalDateTime quoteDeadline;
    @Column(nullable = false) private long expiresAt;
    @Column(nullable = false, length = 10) private String paymentMode;
    @Column(nullable = false, length = 100) private String deploymentReference;
    @Column(length = 100) private String sessionReference;
    @Column(length = 2000) private String checkoutUrl;
    /** Only verified webhook evidence establishes this link. Historical attempts remain unbound. */
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "settled_payment_id") private Payment settledPayment;
    @Column(nullable = false) private Instant createdAt;
    public Long getId() { return id; }
    public Booking getBooking() { return booking; } public void setBooking(Booking value) { booking = value; }
    public PaymentType getPaymentType() { return paymentType; } public void setPaymentType(PaymentType value) { paymentType = value; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal value) { amount = value; }
    public LocalDateTime getQuoteDeadline() { return quoteDeadline; } public void setQuoteDeadline(LocalDateTime value) { quoteDeadline = value; }
    public long getExpiresAt() { return expiresAt; } public void setExpiresAt(long value) { expiresAt = value; }
    public String getPaymentMode() { return paymentMode; } public void setPaymentMode(String value) { paymentMode = value; }
    public String getDeploymentReference() { return deploymentReference; } public void setDeploymentReference(String value) { deploymentReference = value; }
    public String getSessionReference() { return sessionReference; } public void setSessionReference(String value) { sessionReference = value; }
    public String getCheckoutUrl() { return checkoutUrl; } public void setCheckoutUrl(String value) { checkoutUrl = value; }
    public Payment getSettledPayment() { return settledPayment; } public void setSettledPayment(Payment value) { settledPayment = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
}
