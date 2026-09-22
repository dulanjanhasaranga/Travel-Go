package com.travelgo.controller;
import com.travelgo.service.AccountRecoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpSession;
@Controller
public class AccountRecoveryController {
 private final AccountRecoveryService recovery;
 public AccountRecoveryController(AccountRecoveryService recovery){this.recovery=recovery;}
 @GetMapping("/auth/forgot-password") public String forgot(Model model){model.addAttribute("recoveryConfigured",recovery.isConfigured());return "auth/forgot-password";}
 @PostMapping("/auth/forgot-password") public String request(@RequestParam String email,HttpSession session,RedirectAttributes flash){
  Long previous=(Long)session.getAttribute("lastRecoveryRequest");long now=System.currentTimeMillis();
  if(previous!=null&&now-previous<60000){flash.addFlashAttribute("errorMessage","Please wait a minute before requesting another link.");return "redirect:/auth/forgot-password";}
  session.setAttribute("lastRecoveryRequest",now);
  try {recovery.request(email);flash.addFlashAttribute("successMessage","If an active account matches that address, a reset link has been sent. Check your inbox and spam folder.");}
  catch(Exception e){flash.addFlashAttribute("errorMessage","Email recovery is currently unavailable. Please contact our travel team for account assistance.");}
  return "redirect:/auth/forgot-password";
 }
 @GetMapping("/auth/reset-password") public String reset(@RequestParam(required=false) String token,Model model){model.addAttribute("token",token);return "auth/reset-password";}
 @PostMapping("/auth/reset-password") public String reset(@RequestParam String token,@RequestParam String password,@RequestParam String confirmPassword,Model model,RedirectAttributes flash){
  try {recovery.reset(token,password,confirmPassword);flash.addFlashAttribute("successMessage","Your password has been changed. Sign in with your new password.");return "redirect:/auth/login";}
  catch(IllegalArgumentException e){model.addAttribute("token",token);model.addAttribute("errorMessage",e.getMessage());return "auth/reset-password";}
 }
}
