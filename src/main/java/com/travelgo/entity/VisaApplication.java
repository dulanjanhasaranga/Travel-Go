package com.travelgo.entity;

import com.travelgo.enums.VisaStatus;
import com.travelgo.enums.VisaType;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "visa_applications")
public class VisaApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisaType visaType;

    @Column(precision = 10, scale = 2)
    private BigDecimal visaCharge;

    @Column(precision = 10, scale = 2)
    private BigDecimal documentationCharge;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisaStatus status;

    private LocalDateTime paymentExpiresAt;

    @Column(columnDefinition = "TEXT")
    private String officerRemarks;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by_id")
    private User processedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }
    public VisaType getVisaType() { return visaType; }
    public void setVisaType(VisaType visaType) { this.visaType = visaType; }
    public BigDecimal getVisaCharge() { return visaCharge; }
    public void setVisaCharge(BigDecimal visaCharge) { this.visaCharge = visaCharge; }
    public BigDecimal getDocumentationCharge() { return documentationCharge; }
    public void setDocumentationCharge(BigDecimal documentationCharge) { this.documentationCharge = documentationCharge; }
    public BigDecimal getTotalCharge() {
        BigDecimal total = BigDecimal.ZERO;
        if (visaCharge != null) total = total.add(visaCharge);
        if (documentationCharge != null) total = total.add(documentationCharge);
        return total;
    }
    public VisaStatus getStatus() { return status; }
    public void setStatus(VisaStatus status) { this.status = status; }
    public LocalDateTime getPaymentExpiresAt() { return paymentExpiresAt; }
    public void setPaymentExpiresAt(LocalDateTime paymentExpiresAt) { this.paymentExpiresAt = paymentExpiresAt; }
    public String getOfficerRemarks() { return officerRemarks; }
    public void setOfficerRemarks(String officerRemarks) { this.officerRemarks = officerRemarks; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public User getProcessedBy() { return processedBy; }
    public void setProcessedBy(User processedBy) { this.processedBy = processedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
