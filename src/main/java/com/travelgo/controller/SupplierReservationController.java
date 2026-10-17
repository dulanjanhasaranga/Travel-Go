package com.travelgo.controller;
import com.travelgo.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
@RequestMapping("/staff/bookings/{id}/suppliers")
public class SupplierReservationController {
    private final BookingService bookings;private final SupplierReservationService suppliers;
    public SupplierReservationController(BookingService bookings,SupplierReservationService suppliers){this.bookings=bookings;this.suppliers=suppliers;}
    @GetMapping public String page(@PathVariable Long id,Model model){
        model.addAttribute("booking",bookings.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("hold",suppliers.forBooking(id).orElse(null));return "staff/supplier-confirmation";
    }
    @PostMapping public String confirm(@PathVariable Long id,@RequestParam(required=false) Long expectedVersion,@RequestParam String supplier,
          @RequestParam String reference,@RequestParam String notes,@RequestParam(defaultValue="false") boolean allServicesConfirmed,RedirectAttributes flash){
        try{suppliers.confirm(id,expectedVersion,supplier,reference,notes,allServicesConfirmed);flash.addFlashAttribute("successMessage","Supplier confirmation saved.");}
        catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/staff/bookings/"+id+"/suppliers";
    }
}
