package com.travelgo.controller;

import com.travelgo.entity.Destination;
import com.travelgo.service.DestinationService;
import com.travelgo.util.ValidationHelper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for Destination management in Admin panel.
 */
@Controller
@RequestMapping("/staff/destinations")
public class AdminDestinationController {

    private final DestinationService destinationService;

    public AdminDestinationController(DestinationService destinationService) {
        this.destinationService = destinationService;
    }

    @GetMapping
    public String listDestinations(Model model) {
        model.addAttribute("destinations", destinationService.findAll());
        return "staff/destinations";
    }

    @PostMapping("/create")
    public String createDestination(@RequestParam("country") String country,
                                   @RequestParam("city") String city,
                                   @RequestParam(value = "description", required = false) String description,
                                   @RequestParam(value = "image", required = false) String image,
                                   RedirectAttributes redirectAttributes) {
        try {
            Destination dest = new Destination();
            dest.setCountry(country);
            dest.setCity(city);
            dest.setDescription(description);
            dest.setImage(image);
            destinationService.save(dest);
            redirectAttributes.addFlashAttribute("successMessage", "Destination created successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved. Check for duplicate names or linked records and try again.")));
        }
        return "redirect:/staff/destinations";
    }

    @PostMapping("/{id}/edit")
    public String editDestination(@PathVariable("id") Long id,
                                 @RequestParam("country") String country,
                                 @RequestParam("city") String city,
                                 @RequestParam(value = "description", required = false) String description,
                                 @RequestParam(value = "image", required = false) String image,
                                 RedirectAttributes redirectAttributes) {
        try {
            Destination dest = destinationService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Destination not found"));
            dest.setCountry(country);
            dest.setCity(city);
            dest.setDescription(description);
            dest.setImage(image);
            destinationService.save(dest);
            redirectAttributes.addFlashAttribute("successMessage", "Destination updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved. Check for duplicate names or linked records and try again.")));
        }
        return "redirect:/staff/destinations";
    }

    @PostMapping("/{id}/delete")
    public String deleteDestination(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            destinationService.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Destination permanently deleted from the database.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved. Check for duplicate names or linked records and try again.")));
        }
        return "redirect:/staff/destinations";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes attributes) {
        try { destinationService.setActive(id,true); attributes.addFlashAttribute("successMessage","Destination activated."); }
        catch (IllegalArgumentException e) { attributes.addFlashAttribute("errorMessage",e.getMessage()); }
        return "redirect:/staff/destinations";
    }
}
