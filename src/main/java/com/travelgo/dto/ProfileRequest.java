package com.travelgo.dto;
import jakarta.validation.constraints.*;
public class ProfileRequest {
    @NotBlank(message="Enter your name.") @Size(max=100,message="Use up to 100 characters for your name.") private String name;
    @Size(max=20,message="Use up to 20 characters for your phone number.") private String phone;
    @Size(max=1000,message="Use up to 1,000 characters for your address.") private String address;
    public String getName(){return name;} public void setName(String value){name=value;}
    public String getPhone(){return phone;} public void setPhone(String value){phone=value;}
    public String getAddress(){return address;} public void setAddress(String value){address=value;}
}
