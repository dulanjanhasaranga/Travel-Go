package com.travelgo.entity;

import com.travelgo.enums.BookingStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique=true,length=100) private String requestKey;
    @Column(length=64) private String requestFingerprint;
    public String getRequestKey(){return requestKey;} public void setRequestKey(String v){requestKey=v;}
    public String getRequestFingerprint(){return requestFingerprint;} public void setRequestFingerprint(String v){requestFingerprint=v;}

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_package_id", nullable = false)
    private TourPackage tourPackage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "departure_id")
    private Departure departure;

    @NotNull(message = "Travel date is required")
    @Column(nullable = false)
    private LocalDate travelDate;

    @NotNull(message = "Number of travelers is required")
    @Min(value = 1, message = "Must have at least 1 traveler")
    @Column(nullable = false)
    private Integer numberOfTravelers;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal packageUnitPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPackageAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus bookingStatus;

    private LocalDateTime packagePaymentDeadline;

    @Column(columnDefinition = "TEXT")
    private String declineReason;

    private LocalDateTime declinedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declined_by_id")
    private User declinedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Traveler> travelers = new ArrayList<>();

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
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public TourPackage getTourPackage() { return tourPackage; }
    public void setTourPackage(TourPackage tourPackage) { this.tourPackage = tourPackage; }
    public Departure getDeparture() { return departure; }
    public void setDeparture(Departure departure) { this.departure = departure; }
    public LocalDate getTravelDate() { return travelDate; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }
    public Integer getNumberOfTravelers() { return numberOfTravelers; }
    public void setNumberOfTravelers(Integer numberOfTravelers) { this.numberOfTravelers = numberOfTravelers; }
    public BigDecimal getPackageUnitPrice() { return packageUnitPrice; }
    public void setPackageUnitPrice(BigDecimal packageUnitPrice) { this.packageUnitPrice = packageUnitPrice; }
    public BigDecimal getTotalPackageAmount() { return totalPackageAmount; }
    public void setTotalPackageAmount(BigDecimal totalPackageAmount) { this.totalPackageAmount = totalPackageAmount; }
    public BookingStatus getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(BookingStatus bookingStatus) { this.bookingStatus = bookingStatus; }
    public LocalDateTime getPackagePaymentDeadline() { return packagePaymentDeadline; }
    public void setPackagePaymentDeadline(LocalDateTime packagePaymentDeadline) { this.packagePaymentDeadline = packagePaymentDeadline; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<Traveler> getTravelers() { return travelers; }
    public void setTravelers(List<Traveler> travelers) { this.travelers = travelers; }
    public String getDeclineReason() { return declineReason; }
    public void setDeclineReason(String declineReason) { this.declineReason = declineReason; }
    public LocalDateTime getDeclinedAt() { return declinedAt; }
    public void setDeclinedAt(LocalDateTime declinedAt) { this.declinedAt = declinedAt; }
    public User getDeclinedBy() { return declinedBy; }
    public void setDeclinedBy(User declinedBy) { this.declinedBy = declinedBy; }
}
