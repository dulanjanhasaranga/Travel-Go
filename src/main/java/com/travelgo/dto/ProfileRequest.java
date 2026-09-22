package com.travelgo.dto;
import jakarta.validation.constraints.*;
public class ProfileRequest {
    @NotBlank @Size(max=100) private String name;
    @Size(max=20) private String phone;
    @Size(max=1000) private String address;
    public String getName(){return name;} public void setName(String value){name=value;}
    public String getPhone(){return phone;} public void setPhone(String value){phone=value;}
    public String getAddress(){return address;} public void setAddress(String value){address=value;}
}
