package com.travelgo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable evidence of an offline reimbursement already completed outside TravelGO. */
@Entity
@Table(name = "offline_refund_settlements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_offline_refund", columnNames = "refund_id"),
        @UniqueConstraint(name = "uk_offline_refund_reference", columnNames = {"channel", "reference"})})
public class OfflineRefundSettlement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "refund_id", nullable = false) private Refund refund;
    @Column(nullable = false, length = 20) private String channel;
    @Column(nullable = false, length = 120) private String reference;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "evidence_source", nullable = false, length = 1000) private String evidenceSource;
    @Column(name = "reimbursed_at", nullable = false) private LocalDateTime reimbursedAt;
    @Column(name = "recorded_at", nullable = false) private LocalDateTime recordedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recorded_by_id", nullable = false) private User recordedBy;
    @Column(name = "original_payment_method", nullable = false, length = 100) private String originalPaymentMethod;
    @Column(name = "original_payment_reference", nullable = false, length = 200) private String originalPaymentReference;
    @Column(name = "original_payment_amount", nullable = false, precision = 10, scale = 2) private BigDecimal originalPaymentAmount;
    public OfflineRefundSettlement() { }
    public OfflineRefundSettlement(Refund refund, String channel, String reference, BigDecimal amount, String currency,
            String evidenceSource, LocalDateTime reimbursedAt, LocalDateTime recordedAt, User recordedBy) {
        this.refund = refund; this.channel = channel; this.reference = reference; this.amount = amount; this.currency = currency;
        this.evidenceSource = evidenceSource; this.reimbursedAt = reimbursedAt; this.recordedAt = recordedAt; this.recordedBy = recordedBy;
        this.originalPaymentMethod = refund.getPayment().getPaymentMethod(); this.originalPaymentReference = refund.getPayment().getTransactionReference();
        this.originalPaymentAmount = refund.getPayment().getAmount();
    }
    public Long getId() { return id; }
    public Refund getRefund() { return refund; }
    public String getChannel() { return channel; }
    public String getReference() { return reference; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getEvidenceSource() { return evidenceSource; }
    public LocalDateTime getReimbursedAt() { return reimbursedAt; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public User getRecordedBy() { return recordedBy; }
    public String getOriginalPaymentMethod() { return originalPaymentMethod; }
    public String getOriginalPaymentReference() { return originalPaymentReference; }
    public BigDecimal getOriginalPaymentAmount() { return originalPaymentAmount; }
}
