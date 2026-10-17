package com.travelgo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Billing contact is confirmed by the customer; never fill in a fictitious address. */
public class PayHereBillingRequest {
    @NotNull @Positive private Long bookingId;
    @NotBlank @Pattern(regexp="VISA_DOCUMENTATION|FULL_PACKAGE") private String paymentType;
    @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) private BigDecimal amount;
    @NotBlank @Size(max=50) private String firstName;
    @NotBlank @Size(max=50) private String lastName;
    @NotBlank @Email @Size(max=150) private String email;
    @NotBlank @Pattern(regexp="\\+?[0-9][0-9 ()-]{6,19}") private String phone;
    @NotBlank @Size(max=200) private String address;
    @NotBlank @Size(max=100) private String city;
    @NotBlank @Size(max=100) private String country;
    public Long getBookingId(){return bookingId;} public void setBookingId(Long v){bookingId=v;}
    public String getPaymentType(){return paymentType;} public void setPaymentType(String v){paymentType=v;}
    public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=v;}
    public String getFirstName(){return firstName;} public void setFirstName(String v){firstName=v;}
    public String getLastName(){return lastName;} public void setLastName(String v){lastName=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
    public String getAddress(){return address;} public void setAddress(String v){address=v;}
    public String getCity(){return city;} public void setCity(String v){city=v;}
    public String getCountry(){return country;} public void setCountry(String v){country=v;}
}
