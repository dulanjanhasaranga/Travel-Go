package com.travelgo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tour_packages")
public class TourPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition="TEXT") private String itinerary;
    @Column(columnDefinition="TEXT") private String excludedServices;
    @Column(columnDefinition="TEXT") private String travelerInformation;
    public String getItinerary(){return itinerary;} public void setItinerary(String v){itinerary=v;}
    public String getExcludedServices(){return excludedServices;} public void setExcludedServices(String v){excludedServices=v;}
    public String getTravelerInformation(){return travelerInformation;} public void setTravelerInformation(String v){travelerInformation=v;}

    @NotBlank(message = "Name is required")
    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private PackageCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_id", nullable = false)
    private Destination destination;

    @Column(columnDefinition = "TEXT")
    private String description;

    @NotNull(message = "Base price is required")
    @Min(value = 0, message = "Base price cannot be negative")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @NotNull(message = "Duration is required")
    @Min(value = 1, message = "Duration must be at least 1 day")
    @Column(nullable = false)
    private Integer durationDays;

    private String flightDetails;

    @Column(columnDefinition = "TEXT")
    private String includedServices;

    @NotNull(message = "Max capacity is required")
    @Min(value = 1, message = "Max capacity must be at least 1")
    @Column(nullable = false)
    private Integer maxCapacity;

    private String image;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "tour_package_transports",
        joinColumns = @JoinColumn(name = "tour_package_id"),
        inverseJoinColumns = @JoinColumn(name = "transport_id")
    )
    private java.util.List<Transport> transports = new java.util.ArrayList<>();
    public java.util.List<Transport> getTransports() { return transports; }
    public void setTransports(java.util.List<Transport> transports) { this.transports = transports; }

    @Column(nullable = false)
    private boolean isActive = true;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public PackageCategory getCategory() { return category; }
    public void setCategory(PackageCategory category) { this.category = category; }
    public Destination getDestination() { return destination; }
    public void setDestination(Destination destination) { this.destination = destination; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }
    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
    public String getFlightDetails() { return flightDetails; }
    public void setFlightDetails(String flightDetails) { this.flightDetails = flightDetails; }
    public String getIncludedServices() { return includedServices; }
    public void setIncludedServices(String includedServices) { this.includedServices = includedServices; }
    public Integer getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Integer maxCapacity) { this.maxCapacity = maxCapacity; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }
    @Column(nullable = false)
    private boolean featured = false;

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public boolean isFeatured() { return featured; }
    public void setFeatured(boolean featured) { this.featured = featured; }
}
