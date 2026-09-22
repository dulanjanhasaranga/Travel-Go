package com.travelgo.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record InquiryRequest(@NotBlank @Size(max=100) String senderName,@NotBlank @Email @Size(max=150) String senderEmail,
 @NotBlank @Size(max=200) String subject,@NotBlank @Size(max=5000) String message,
 @Size(max=20) String phone,@Pattern(regexp="GENERAL|PACKAGE|VISA|GROUP") String inquiryType,
 Long destinationId,Long packageId,LocalDate preferredDate,@Min(1) @Max(100) Integer travelerCount,
 @Pattern(regexp="|UNDER_1000|1000_3000|3000_5000|OVER_5000") String budgetRange,
 @Size(max=64) String submissionKey) {}
