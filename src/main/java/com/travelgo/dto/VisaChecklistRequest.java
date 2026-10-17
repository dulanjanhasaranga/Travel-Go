package com.travelgo.dto;

import com.travelgo.enums.VisaType;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record VisaChecklistRequest(
        @NotNull(message = "Choose a destination.") @Positive(message = "Choose a valid destination.") Long destinationId,
        @NotNull(message = "Choose a visa category.") VisaType visaType,
        @Size(max = 1000, message = "Use a source link no longer than 1,000 characters.") String sourceUrl,
        LocalDate sourceReviewedOn,
        @Min(value = 1, message = "Enter a minimum lead time of at least 1 day.") @Max(value = 365, message = "The minimum lead time cannot exceed 365 days.") Integer minimumLeadDays,
        @Min(value = 1, message = "Enter a processing estimate of at least 1 day.") @Max(value = 365, message = "The processing estimate cannot exceed 365 days.") Integer estimatedProcessingMinDays,
        @Min(value = 1, message = "Enter a processing estimate of at least 1 day.") @Max(value = 365, message = "The processing estimate cannot exceed 365 days.") Integer estimatedProcessingMaxDays,
        @Size(max = 2000, message = "Use up to 2,000 characters for customer guidance.") String guidance,
        @Size(max = 6000, message = "Use up to 6,000 characters for required documents.") String requiredDocuments,
        @Size(max = 6000, message = "Use up to 6,000 characters for optional documents.") String optionalDocuments) {}
