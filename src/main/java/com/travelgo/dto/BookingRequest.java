package com.travelgo.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public record BookingRequest(@NotNull Long packageId, @NotNull LocalDate travelDate,
        @NotNull @Positive Integer numberOfTravelers, Long hotelId,
        @NotEmpty List<@Valid TravelerInput> travelers) {
    public record TravelerInput(@NotBlank String name, @NotBlank String passport,
            @NotNull @Past LocalDate dob, @NotBlank String gender, @NotBlank String nationality) {}
    public static List<TravelerInput> travelers(List<String> names, List<String> passports,
            List<LocalDate> dobs, List<String> genders, List<String> nationalities) {
        if (names == null || passports == null || dobs == null || genders == null || nationalities == null
                || names.size() != passports.size() || names.size() != dobs.size()
                || names.size() != genders.size() || names.size() != nationalities.size())
            throw new IllegalArgumentException("Complete all traveler details.");
        return java.util.stream.IntStream.range(0, names.size())
            .mapToObj(i -> new TravelerInput(names.get(i), passports.get(i), dobs.get(i), genders.get(i), nationalities.get(i))).toList();
    }
}
