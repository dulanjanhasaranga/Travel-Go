package com.travelgo.controller;
import com.travelgo.dto.ProfileRequest;
import com.travelgo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
@Controller
@RequestMapping("/customer/profile")
public class CustomerProfileController {
    private final UserService users;
    public CustomerProfileController(UserService users){this.users=users;}
    @GetMapping public String profile(@AuthenticationPrincipal UserDetails principal, Model model){
        var user=users.getUserByEmail(principal.getUsername()).orElseThrow();
        var form=new ProfileRequest();form.setName(user.getName());form.setPhone(user.getPhone());form.setAddress(user.getAddress());
        model.addAttribute("profileRequest",form);return "customer/profile";
    }
    @PostMapping public String save(@Valid @ModelAttribute ProfileRequest profileRequest, BindingResult result, RedirectAttributes flash){
        if(result.hasErrors())return "customer/profile";
        users.updateOwnProfile(profileRequest);
        flash.addFlashAttribute("successMessage","Your profile has been updated.");return "redirect:/customer/profile";
    }
}
