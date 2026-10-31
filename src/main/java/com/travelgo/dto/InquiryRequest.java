package com.travelgo.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record InquiryRequest(
 @NotBlank(message="Enter your name.") @Size(max=100,message="Use up to 100 characters for your name.") String senderName,
 @NotBlank(message="Enter your email address.") @Email(message="Enter a valid email address.") @Size(max=150,message="Use up to 150 characters for your email address.") String senderEmail,
 @NotBlank(message="Enter a subject.") @Size(max=200,message="Use up to 200 characters for the subject.") String subject,
 @NotBlank(message="Enter your message.") @Size(max=5000,message="Use up to 5,000 characters for your message.") String message,
 @Size(max=20,message="Use up to 20 characters for your phone number.") String phone,
 @Pattern(regexp="GENERAL|PACKAGE|VISA|GROUP",message="Choose an inquiry type.") String inquiryType,
 Long destinationId,Long packageId,LocalDate preferredDate,
 @Min(value=1,message="Include at least one traveler.") @Max(value=100,message="Enter no more than 100 travelers.") Integer travelerCount,
 @Pattern(regexp="|UNDER_1000|1000_3000|3000_5000|OVER_5000",message="Choose one of the listed budget ranges.") String budgetRange,
 @Size(max=64,message="Refresh the inquiry form before submitting.") String submissionKey) {}
