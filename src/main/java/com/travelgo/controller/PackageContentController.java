package com.travelgo.controller;
import com.travelgo.repository.TourPackageRepository;
import com.travelgo.service.WorkflowRules;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
@Controller @RequestMapping("/staff/packages")
public class PackageContentController {
 private final TourPackageRepository packages;private final WorkflowRules rules;private final com.travelgo.service.TourPackageService service;
 public PackageContentController(TourPackageRepository packages,WorkflowRules rules,com.travelgo.service.TourPackageService service){this.packages=packages;this.rules=rules;this.service=service;}
 @GetMapping("/{id}/content") public String content(@PathVariable Long id,Model model){rules.requireRole("TRAVEL_CONSULTANT");model.addAttribute("pkg",packages.findById(id).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)));return "staff/package-content";}
 @PostMapping("/{id}/content")
 public String update(@PathVariable Long id,@RequestParam String itinerary,@RequestParam String excludedServices,@RequestParam String travelerInformation,@RequestParam(defaultValue="false")boolean active,RedirectAttributes flash){
  try {service.updateContent(id,itinerary,excludedServices,travelerInformation,active);flash.addFlashAttribute("successMessage","Package content updated.");}
  catch(Exception e){flash.addFlashAttribute("errorMessage",com.travelgo.service.CustomerErrorMessage.from(e));}
  return "redirect:/staff/packages/"+id+"/content";
 }
}
