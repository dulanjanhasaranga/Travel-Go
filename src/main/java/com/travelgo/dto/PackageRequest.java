package com.travelgo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** The existing staff form, validated before changing any persisted package. */
public record PackageRequest(
        @NotBlank(message="Enter a package name.") @Size(max=255,message="Use up to 255 characters for the package name.") String name,
        @NotNull(message="Choose a package category.") Long categoryId,
        @NotNull(message="Choose a destination.") Long destinationId,
        @Size(max=10000,message="Use up to 10,000 characters for the description.") String description,
        @NotNull(message="Enter the price per traveler.") @DecimalMin(value="0.01",message="Enter a price of at least 0.01.") @Digits(integer=8, fraction=2,message="Use up to 8 digits before the decimal point and 2 after it.") BigDecimal basePrice,
        @NotNull(message="Enter the duration in days.") @Min(value=1,message="A tour must last at least 1 day.") @Max(value=365,message="A tour cannot exceed 365 days.") Integer durationDays,
        @Size(max=255,message="Use up to 255 characters for flight details.") String flightDetails,
        @Size(max=10000,message="Use up to 10,000 characters for included services.") String includedServices,
        @NotNull(message="Enter the traveler capacity.") @Min(value=1,message="Allow at least 1 traveler.") @Max(value=1000,message="The traveler capacity cannot exceed 1,000.") Integer maxCapacity,
        @Size(max=255,message="Use an image reference no longer than 255 characters.") String image,
        @Size(max=10000,message="Use up to 10,000 characters for the itinerary.") String itinerary,
        @Size(max=10000,message="Use up to 10,000 characters for excluded services.") String excludedServices,
        @Size(max=10000,message="Use up to 10,000 characters for traveler information.") String travelerInformation,
        String galleryUrls) {}
