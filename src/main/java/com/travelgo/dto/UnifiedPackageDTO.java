package com.travelgo.dto;

import java.math.BigDecimal;

public class UnifiedPackageDTO {
    private String idPrefix; // "TOUR-" or "HOTEL-"
    private Long id;
    private String type; // "DESTINATION" or "HOTEL"
    private String name;
    private String categoryName; // Category for tour, "Accommodation" for hotel
    private String destination; // "City, Country"
    private BigDecimal basePrice;
    private Integer durationDays; // Can be null for hotel (or 1 night)
    private Integer maxCapacity; // Can be null for hotel
    private Integer starRating; // Can be null for tour
    private String image;
    private boolean active;
    private String includedServices;

    public UnifiedPackageDTO() {}

    public String getIdPrefix() { return idPrefix; }
    public void setIdPrefix(String idPrefix) { this.idPrefix = idPrefix; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }

    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }

    public Integer getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Integer maxCapacity) { this.maxCapacity = maxCapacity; }
    
    public Integer getStarRating() { return starRating; }
    public void setStarRating(Integer starRating) { this.starRating = starRating; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getIncludedServices() { return includedServices; }
    public void setIncludedServices(String includedServices) { this.includedServices = includedServices; }
}
