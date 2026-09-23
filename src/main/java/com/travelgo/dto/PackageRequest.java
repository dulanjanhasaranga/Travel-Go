package com.travelgo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** The existing staff form, validated before changing any persisted package. */
public record PackageRequest(
        @NotBlank @Size(max=255) String name,
        @NotNull Long categoryId,
        @NotNull Long destinationId,
        @Size(max=10000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer=8, fraction=2) BigDecimal basePrice,
        @NotNull @Min(1) @Max(365) Integer durationDays,
        @Size(max=255) String flightDetails,
        @Size(max=10000) String includedServices,
        @NotNull @Min(1) @Max(1000) Integer maxCapacity,
        @Size(max=255) String image,
        @Size(max=10000) String itinerary,
        @Size(max=10000) String excludedServices,
        @Size(max=10000) String travelerInformation) {}
