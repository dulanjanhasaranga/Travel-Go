package com.travelgo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for customer registration form.
 */
public class RegisterRequest {

    @NotBlank(message = "Enter your name.")
    @Size(min = 2, max = 100, message = "Use between 2 and 100 characters for the name.")
    private String name;

    @NotBlank(message = "Enter an email address.")
    @Email(message = "Enter a valid email address.")
    @Size(max=150, message = "Use up to 150 characters for the email address.")
    private String email;

    @NotBlank(message = "Enter a password.")
    @Size(min = 12, max = 72, message = "Use between 12 and 72 characters for the password.")
    private String password;

    @NotBlank(message = "Enter the password again.")
    private String confirmPassword;

    @Size(max = 20, message = "Use up to 20 characters for the phone number.")
    private String phone;

    private String address;

    // ---- Getters and Setters ----

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}
