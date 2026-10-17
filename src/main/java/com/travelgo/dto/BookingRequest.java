package com.travelgo.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public record BookingRequest(@NotNull(message = "Choose a tour package.") Long packageId,
        @NotNull(message = "Choose a travel date.") LocalDate travelDate,
        @NotNull(message = "Enter the number of travelers.") @Positive(message = "Include at least one traveler.") Integer numberOfTravelers,
        Long hotelId, @NotEmpty(message = "Enter the details for every traveler.") List<@Valid TravelerInput> travelers) {
    public record TravelerInput(
            @NotBlank(message = "Enter the traveler's full name.") @Size(max = 255, message = "Use up to 255 characters for the traveler's name.") String name,
            @NotBlank(message = "Enter the traveler's passport number.") @Size(max = 255, message = "Use up to 255 characters for the passport number.") String passport,
            @NotNull(message = "Enter the traveler's date of birth.") @Past(message = "The date of birth must be before today.") LocalDate dob,
            @NotBlank(message = "Select the traveler's gender.") @Size(max = 255, message = "Use up to 255 characters for gender.") String gender,
            @NotBlank(message = "Enter the traveler's nationality.") @Size(max = 255, message = "Use up to 255 characters for nationality.") String nationality) {}
    public static List<TravelerInput> travelers(List<String> names, List<String> passports,
            List<LocalDate> dobs, List<String> genders, List<String> nationalities) {
        if (names == null || passports == null || dobs == null || genders == null || nationalities == null
                || names.size() != passports.size() || names.size() != dobs.size()
                || names.size() != genders.size() || names.size() != nationalities.size())
            throw new IllegalArgumentException("Complete the details for every traveler.");
        return java.util.stream.IntStream.range(0, names.size())
            .mapToObj(i -> new TravelerInput(names.get(i), passports.get(i), dobs.get(i), genders.get(i), nationalities.get(i))).toList();
    }
}
