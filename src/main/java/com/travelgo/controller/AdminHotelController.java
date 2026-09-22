package com.travelgo.controller;

import com.travelgo.entity.Hotel;
import com.travelgo.service.DestinationService;
import com.travelgo.service.HotelService;
import com.travelgo.util.ValidationHelper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Controller for Hotel management in Admin panel.
 */
@Controller
@RequestMapping("/staff/hotels")
public class AdminHotelController {

    private final HotelService hotelService;
    private final DestinationService destinationService;

    public AdminHotelController(HotelService hotelService, DestinationService destinationService) {
        this.hotelService = hotelService;
        this.destinationService = destinationService;
    }

    @GetMapping
    public String listHotels(Model model) {
        model.addAttribute("hotels", hotelService.findAll());
        model.addAttribute("destinations", destinationService.findAll());
        return "staff/hotels";
    }

    @PostMapping("/create")
    public String createHotel(@RequestParam("name") String name,
                             @RequestParam("destinationId") Long destinationId,
                             @RequestParam("address") String address,
                             @RequestParam(value = "starRating", required = false) Integer starRating,
                             @RequestParam("pricePerNight") BigDecimal pricePerNight,
                             @RequestParam(value = "description", required = false) String description,
                             @RequestParam(value = "image", required = false) String image,
                             RedirectAttributes redirectAttributes) {
        try {
            Hotel hotel = new Hotel();
            hotel.setName(name);
            hotel.setDestination(destinationService.findById(destinationId)
                    .orElseThrow(() -> new IllegalArgumentException("Destination not found")));
            hotel.setAddress(address);
            hotel.setStarRating(starRating);
            hotel.setPricePerNight(pricePerNight);
            hotel.setDescription(description);
            hotel.setImage(image);
            hotelService.save(hotel);
            redirectAttributes.addFlashAttribute("successMessage", "Hotel created successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved. Check for duplicate names or linked records and try again.")));
        }
        return "redirect:/staff/hotels";
    }

    @PostMapping("/{id}/edit")
    public String editHotel(@PathVariable("id") Long id,
                           @RequestParam("name") String name,
                           @RequestParam("destinationId") Long destinationId,
                           @RequestParam("address") String address,
                           @RequestParam(value = "starRating", required = false) Integer starRating,
                           @RequestParam("pricePerNight") BigDecimal pricePerNight,
                           @RequestParam(value = "description", required = false) String description,
                           @RequestParam(value = "image", required = false) String image,
                           RedirectAttributes redirectAttributes) {
        try {
            Hotel hotel = hotelService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Hotel not found"));
            hotel.setName(name);
            hotel.setDestination(destinationService.findById(destinationId)
                    .orElseThrow(() -> new IllegalArgumentException("Destination not found")));
            hotel.setAddress(address);
            hotel.setStarRating(starRating);
            hotel.setPricePerNight(pricePerNight);
            hotel.setDescription(description);
            hotel.setImage(image);
            hotelService.save(hotel);
            redirectAttributes.addFlashAttribute("successMessage", "Hotel updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved. Check for duplicate names or linked records and try again.")));
        }
        return "redirect:/staff/hotels";
    }

    @PostMapping("/{id}/delete")
    public String deleteHotel(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            hotelService.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Hotel deactivated. Existing records are preserved.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved. Check for duplicate names or linked records and try again.")));
        }
        return "redirect:/staff/hotels";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes attributes) {
        try { hotelService.setActive(id,true); attributes.addFlashAttribute("successMessage","Hotel activated."); }
        catch (IllegalArgumentException e) { attributes.addFlashAttribute("errorMessage",e.getMessage()); }
        return "redirect:/staff/hotels";
    }
}
