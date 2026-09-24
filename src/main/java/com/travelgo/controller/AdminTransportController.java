package com.travelgo.controller;

import com.travelgo.entity.Transport;
import com.travelgo.service.DestinationService;
import com.travelgo.service.TransportService;
import com.travelgo.util.ValidationHelper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/staff/transports")
public class AdminTransportController {

    private final TransportService transportService;
    private final DestinationService destinationService;

    public AdminTransportController(TransportService transportService, DestinationService destinationService) {
        this.transportService = transportService;
        this.destinationService = destinationService;
    }

    @GetMapping
    public String listTransports(Model model) {
        model.addAttribute("transports", transportService.findAll());
        model.addAttribute("destinations", destinationService.findAll());
        return "staff/transports";
    }

    @PostMapping("/create")
    public String createTransport(@RequestParam("name") String name,
                             @RequestParam("destinationId") Long destinationId,
                             @RequestParam("mode") String mode,
                             @RequestParam("departureLocation") String departureLocation,
                             @RequestParam("arrivalLocation") String arrivalLocation,
                             @RequestParam("price") BigDecimal price,
                             @RequestParam(value = "description", required = false) String description,
                             RedirectAttributes redirectAttributes) {
        try {
            Transport transport = new Transport();
            transport.setName(name);
            transport.setDestination(destinationService.findById(destinationId)
                    .orElseThrow(() -> new IllegalArgumentException("Destination not found")));
            transport.setMode(mode);
            transport.setDepartureLocation(departureLocation);
            transport.setArrivalLocation(arrivalLocation);
            transport.setPrice(price);
            transport.setDescription(description);
            transportService.save(transport);
            redirectAttributes.addFlashAttribute("successMessage", "Transport created successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved.")));
        }
        return "redirect:/staff/transports";
    }

    @PostMapping("/{id}/edit")
    public String editTransport(@PathVariable("id") Long id,
                           @RequestParam("name") String name,
                           @RequestParam("destinationId") Long destinationId,
                           @RequestParam("mode") String mode,
                           @RequestParam("departureLocation") String departureLocation,
                           @RequestParam("arrivalLocation") String arrivalLocation,
                           @RequestParam("price") BigDecimal price,
                           @RequestParam(value = "description", required = false) String description,
                           RedirectAttributes redirectAttributes) {
        try {
            Transport transport = transportService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Transport not found"));
            transport.setName(name);
            transport.setDestination(destinationService.findById(destinationId)
                    .orElseThrow(() -> new IllegalArgumentException("Destination not found")));
            transport.setMode(mode);
            transport.setDepartureLocation(departureLocation);
            transport.setArrivalLocation(arrivalLocation);
            transport.setPrice(price);
            transport.setDescription(description);
            transportService.save(transport);
            redirectAttributes.addFlashAttribute("successMessage", "Transport updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved.")));
        }
        return "redirect:/staff/transports";
    }

    @PostMapping("/{id}/delete")
    public String deleteTransport(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            transportService.deactivate(id);
            redirectAttributes.addFlashAttribute("successMessage", "Transport deactivated.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", ValidationHelper.extractMessage(e, ValidationHelper.extractMessage(e, "The change could not be saved.")));
        }
        return "redirect:/staff/transports";
    }
}
