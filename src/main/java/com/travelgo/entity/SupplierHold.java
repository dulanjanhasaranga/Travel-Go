package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_holds")
public class SupplierHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false)
    private String supplierReference;

    @Column(nullable = false)
    private LocalDateTime holdExpiresAt;

    @Column(nullable = false)
    private boolean isConfirmed = false;

    @Version @Column(nullable=false,columnDefinition="bigint default 0") private Long version=0L;
    @Column(length=200) private String supplierName;
    @Column(length=200) private String confirmationReference;
    private LocalDateTime confirmedAt;
    @ManyToOne(fetch=FetchType.LAZY) private User confirmedBy;
    @Column(columnDefinition="TEXT") private String serviceNotes;
    public Long getVersion(){return version;}
    public String getSupplierName(){return supplierName;} public void setSupplierName(String v){supplierName=v;}
    public String getConfirmationReference(){return confirmationReference;} public void setConfirmationReference(String v){confirmationReference=v;}
    public LocalDateTime getConfirmedAt(){return confirmedAt;} public void setConfirmedAt(LocalDateTime v){confirmedAt=v;}
    public User getConfirmedBy(){return confirmedBy;} public void setConfirmedBy(User v){confirmedBy=v;}
    public String getServiceNotes(){return serviceNotes;} public void setServiceNotes(String v){serviceNotes=v;}
    public boolean hasConfirmationEvidence(){return isConfirmed&&confirmedAt!=null&&confirmedBy!=null&&confirmationReference!=null&&!confirmationReference.isBlank()&&supplierName!=null&&!supplierName.isBlank();}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }
    public String getSupplierReference() { return supplierReference; }
    public void setSupplierReference(String supplierReference) { this.supplierReference = supplierReference; }
    public LocalDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public void setHoldExpiresAt(LocalDateTime holdExpiresAt) { this.holdExpiresAt = holdExpiresAt; }
    public boolean isConfirmed() { return isConfirmed; }
    public void setConfirmed(boolean confirmed) { isConfirmed = confirmed; }
}
