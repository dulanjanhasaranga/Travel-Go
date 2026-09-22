package com.travelgo.controller;

import com.travelgo.entity.TourPackage;
import com.travelgo.service.DestinationService;
import com.travelgo.service.PackageCategoryService;
import com.travelgo.service.TourPackageService;
import com.travelgo.util.ValidationHelper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Controller for Tour Package management in Admin panel.
 */
import com.travelgo.entity.Hotel;
import com.travelgo.service.HotelService;
import com.travelgo.dto.UnifiedPackageDTO;
import org.springframework.http.ResponseEntity;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/staff/packages")
public class AdminTourPackageController {

    private final TourPackageService tourPackageService;
    private final DestinationService destinationService;
    private final PackageCategoryService categoryService;
    private final HotelService hotelService;

    public AdminTourPackageController(TourPackageService tourPackageService,
                                     DestinationService destinationService,
                                     PackageCategoryService categoryService,
                                     HotelService hotelService) {
        this.tourPackageService = tourPackageService;
        this.destinationService = destinationService;
        this.categoryService = categoryService;
        this.hotelService = hotelService;
    }

    @GetMapping
    public String listPackages(Model model) {
        List<UnifiedPackageDTO> unified = new ArrayList<>();
        
        for (TourPackage p : tourPackageService.findAll()) {
            UnifiedPackageDTO dto = new UnifiedPackageDTO();
            dto.setIdPrefix("TOUR-" + p.getId());
            dto.setId(p.getId());
            dto.setType("DESTINATION");
            dto.setName(p.getName());
            dto.setCategoryName(p.getCategory() != null ? p.getCategory().getName() : "");
            dto.setDestination(p.getDestination() != null ? p.getDestination().getCity() + ", " + p.getDestination().getCountry() : "");
            dto.setBasePrice(p.getBasePrice());
            dto.setDurationDays(p.getDurationDays());
            dto.setMaxCapacity(p.getMaxCapacity());
            dto.setImage(p.getImage());
            dto.setActive(p.isActive());
            dto.setIncludedServices(p.getIncludedServices());
            unified.add(dto);
        }
        
        for (Hotel h : hotelService.findAll()) {
            UnifiedPackageDTO dto = new UnifiedPackageDTO();
            dto.setIdPrefix("HOTEL-" + h.getId());
            dto.setId(h.getId());
            dto.setType("HOTEL");
            dto.setName(h.getName());
            dto.setCategoryName("Accommodation");
            dto.setDestination(h.getDestination() != null ? h.getDestination().getCity() + ", " + h.getDestination().getCountry() : "");
            dto.setBasePrice(h.getPricePerNight());
            dto.setStarRating(h.getStarRating());
            dto.setImage(h.getImage());
            dto.setActive(h.isActive());
            dto.setIncludedServices(h.getDescription()); // using description as included services
            unified.add(dto);
        }
        
        model.addAttribute("packages", unified);
        model.addAttribute("destinations", destinationService.findAll());
        model.addAttribute("categories", categoryService.findAll());
        return "staff/packages";
    }

    @PostMapping("/create")
    public String createPackage(@RequestParam("name") String name,
                               @RequestParam("categoryId") Long categoryId,
                               @RequestParam("destinationId") Long destinationId,
                               @RequestParam(value = "description", required = false) String description,
                               @RequestParam("basePrice") BigDecimal basePrice,
                               @RequestParam("durationDays") Integer durationDays,
                               @RequestParam(value = "flightDetails", required = false) String flightDetails,
                               @RequestParam(value = "includedServices", required = false) String includedServices,
                               @RequestParam("maxCapacity") Integer maxCapacity,
                               @RequestParam(value = "image", required = false) String image,
                               RedirectAttributes redirectAttributes) {
        try {
            tourPackageService.savePackage(null, new com.travelgo.dto.PackageRequest(name, categoryId, destinationId,
                    description, basePrice, durationDays, flightDetails, includedServices, maxCapacity, image));
            redirectAttributes.addFlashAttribute("successMessage", "Tour package created successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/staff/packages";
    }

    @PostMapping("/{id}/edit")
    public String editPackage(@PathVariable("id") Long id,
                             @RequestParam("name") String name,
                             @RequestParam("categoryId") Long categoryId,
                             @RequestParam("destinationId") Long destinationId,
                             @RequestParam(value = "description", required = false) String description,
                             @RequestParam("basePrice") BigDecimal basePrice,
                             @RequestParam("durationDays") Integer durationDays,
                             @RequestParam(value = "flightDetails", required = false) String flightDetails,
                             @RequestParam(value = "includedServices", required = false) String includedServices,
                             @RequestParam("maxCapacity") Integer maxCapacity,
                             @RequestParam(value = "image", required = false) String image,
                             RedirectAttributes redirectAttributes) {
        try {
            tourPackageService.savePackage(id, new com.travelgo.dto.PackageRequest(name, categoryId, destinationId,
                    description, basePrice, durationDays, flightDetails, includedServices, maxCapacity, image));
            redirectAttributes.addFlashAttribute("successMessage", "Tour package updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/staff/packages";
    }

    @PostMapping("/{id}/delete")
    public String deletePackage(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            tourPackageService.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Package permanently deleted from the database.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/staff/packages";
    }
    @PostMapping("/api/create")
    @ResponseBody
    public ResponseEntity<?> createPackageApi(@RequestParam("name") String name,
                                @RequestParam("categoryId") Long categoryId,
                                @RequestParam("destinationId") Long destinationId,
                                @RequestParam(value = "description", required = false) String description,
                                @RequestParam("basePrice") BigDecimal basePrice,
                                @RequestParam("durationDays") Integer durationDays,
                                @RequestParam(value = "flightDetails", required = false) String flightDetails,
                                @RequestParam(value = "includedServices", required = false) String includedServices,
                                @RequestParam("maxCapacity") Integer maxCapacity,
                                @RequestParam(value = "image", required = false) String image) {
        try {
            com.travelgo.entity.TourPackage pkg = tourPackageService.savePackage(null, new com.travelgo.dto.PackageRequest(name, categoryId, destinationId,
                    description, basePrice, durationDays, flightDetails, includedServices, maxCapacity, image));
            
            UnifiedPackageDTO dto = new UnifiedPackageDTO();
            dto.setIdPrefix("TOUR-" + pkg.getId());
            dto.setId(pkg.getId());
            dto.setType("DESTINATION");
            dto.setName(pkg.getName());
            dto.setCategoryName(pkg.getCategory() != null ? pkg.getCategory().getName() : "");
            dto.setDestination(pkg.getDestination() != null ? pkg.getDestination().getCity() + ", " + pkg.getDestination().getCountry() : "");
            dto.setBasePrice(pkg.getBasePrice());
            dto.setDurationDays(pkg.getDurationDays());
            dto.setMaxCapacity(pkg.getMaxCapacity());
            dto.setImage(pkg.getImage());
            dto.setActive(pkg.isActive());
            dto.setIncludedServices(pkg.getIncludedServices());

            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(com.travelgo.service.CustomerErrorMessage.from(e));
        }
    }

    @PostMapping("/api/create-hotel")
    @ResponseBody
    public ResponseEntity<?> createHotelApi(@RequestParam("name") String name,
                             @RequestParam("destinationId") Long destinationId,
                             @RequestParam("address") String address,
                             @RequestParam(value = "starRating", required = false) Integer starRating,
                             @RequestParam("pricePerNight") BigDecimal pricePerNight,
                             @RequestParam(value = "description", required = false) String description,
                             @RequestParam(value = "image", required = false) String image) {
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

            UnifiedPackageDTO dto = new UnifiedPackageDTO();
            dto.setIdPrefix("HOTEL-" + hotel.getId());
            dto.setId(hotel.getId());
            dto.setType("HOTEL");
            dto.setName(hotel.getName());
            dto.setCategoryName("Accommodation");
            dto.setDestination(hotel.getDestination() != null ? hotel.getDestination().getCity() + ", " + hotel.getDestination().getCountry() : "");
            dto.setBasePrice(hotel.getPricePerNight());
            dto.setStarRating(hotel.getStarRating());
            dto.setImage(hotel.getImage());
            dto.setActive(hotel.isActive());
            dto.setIncludedServices(hotel.getDescription());

            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body((e instanceof IllegalArgumentException ? e.getMessage() : "Check for duplicate names or linked records and try again."));
        }
    }
}
