package com.travelgo.controller;

import com.travelgo.entity.ContactMessage;
import com.travelgo.service.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for all public-facing pages.
 * These pages are accessible without login.
 */
@Controller
public class PublicController {

    private final SystemSettingsService settingsService;
    private final DestinationService destinationService;
    private final TourPackageService tourPackageService;
    private final PackageCategoryService packageCategoryService;
    private final ContactMessageService contactMessageService;
    private final UserService userService;
    private final HotelService hotelService;
    private final CatalogueDiscoveryService discovery;
    private final com.travelgo.repository.DepartureRepository departureRepository;

    public PublicController(SystemSettingsService settingsService,
                           DestinationService destinationService,
                           TourPackageService tourPackageService,
                           PackageCategoryService packageCategoryService,
                           ContactMessageService contactMessageService,
                           UserService userService,
                           HotelService hotelService, CatalogueDiscoveryService discovery, com.travelgo.repository.DepartureRepository departureRepository) {
        this.settingsService = settingsService;
        this.destinationService = destinationService;
        this.tourPackageService = tourPackageService;
        this.packageCategoryService = packageCategoryService;
        this.contactMessageService = contactMessageService;
        this.userService = userService;
        this.hotelService = hotelService;
        this.discovery = discovery;
        this.departureRepository = departureRepository;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        var destinations = discovery.destinations(null, null, "name");
        model.addAttribute("destinations", destinations);
        model.addAttribute("destinationSummaries", discovery.summaries(destinations));
        model.addAttribute("packages", tourPackageService.findAll().stream().filter(p -> p.isActive() && p.getDestination().isActive()).toList());
        model.addAttribute("valuePackages", discovery.packages(null, null, null, null, null, null, null, null, null, "price-asc").stream().limit(3).toList());
        model.addAttribute("categories", packageCategoryService.findAll());
        return "index";
    }


    @GetMapping("/destinations")
    public String destinations(@RequestParam(value = "search", required = false) String search,
                               @RequestParam(value = "country", required = false) String country,
                               @RequestParam(value = "sort", defaultValue = "name") String sort,
                               @RequestParam(value = "page", defaultValue = "1") int page, Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        var all = discovery.destinations(null, null, "name");
        var matching = discovery.destinations(search, country, sort);
        model.addAttribute("countries", all.stream().map(com.travelgo.entity.Destination::getCountry).distinct().sorted().toList());
        model.addAttribute("destinationOptions", all);
        model.addAttribute("destinationSummaries", discovery.summaries(matching));
        model.addAttribute("destinations", page(matching, page, model));
        model.addAttribute("search", search);
        model.addAttribute("country", country);
        model.addAttribute("sort", sort);
        return "destinations";
    }

    @GetMapping("/destinations/{id}")
    public String destinationDetail(@PathVariable Long id, Model model) {
        var destination = destinationService.findById(id).filter(com.travelgo.entity.Destination::isActive)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        model.addAttribute("settings", settingsService.getSettings());
        model.addAttribute("destination", destination);
        model.addAttribute("packages", discovery.packages(null, id, null, null, null, null, null, null, null, "price-asc"));
        model.addAttribute("hotels", hotelService.findByDestinationId(id).stream().filter(com.travelgo.entity.Hotel::isActive).toList());
        return "destination-detail";
    }

    @GetMapping("/packages")
    public String packages(@RequestParam(value = "search", required = false) String search,
                          @RequestParam(value = "destinationId", required = false) Long destinationId,
                          @RequestParam(value = "categoryId", required = false) Long categoryId,
                          @RequestParam(value = "maxPrice", required = false) java.math.BigDecimal maxPrice,
                          @RequestParam(value = "destinationQuery", required = false) String destinationQuery,
                          @RequestParam(value = "minDays", required = false) Integer minDays,
                          @RequestParam(value = "maxDays", required = false) Integer maxDays,
                          @RequestParam(value = "travelers", required = false) Integer travelers,
                          @RequestParam(value = "travelDate", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate travelDate,
                          @RequestParam(value = "sort", defaultValue = "newest") String sort,
                          @RequestParam(value = "page", defaultValue = "1") int page,
                          Model model) {
        if (maxPrice != null && maxPrice.signum() < 0 || minDays != null && (minDays < 1 || minDays > 365) || maxDays != null && (maxDays < 1 || maxDays > 365)
                || minDays != null && maxDays != null && minDays > maxDays || travelers != null && (travelers < 1 || travelers > 1000))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Use a positive duration and traveler count, and a valid price range.");
        model.addAttribute("settings", settingsService.getSettings());
        var matching = discovery.packages(search, destinationId, destinationQuery, categoryId, maxPrice, minDays, maxDays, travelers, travelDate, sort);
        model.addAttribute("packages", page(matching, page, model));
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("destinations", destinationService.findAll().stream().filter(d -> d.isActive()).toList());
        model.addAttribute("categories", packageCategoryService.findAll());
        model.addAttribute("search", search);
        model.addAttribute("selectedDestinationId", destinationId);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("destinationQuery", destinationQuery != null ? destinationQuery : destinationService.findById(destinationId == null ? -1L : destinationId)
                .map(d -> d.getCity() + ", " + d.getCountry()).orElse(""));
        model.addAttribute("minDays", minDays);
        model.addAttribute("maxDays", maxDays);
        model.addAttribute("travelers", travelers);
        model.addAttribute("travelDate", travelDate);
        model.addAttribute("sort", sort);
        return "packages";
    }

    private <T> java.util.List<T> page(java.util.List<T> items, int requestedPage, Model model) {
        int pageCount = Math.max(1, (items.size() + 8) / 9);
        int current = Math.max(1, Math.min(requestedPage, pageCount));
        model.addAttribute("resultCount", items.size());
        model.addAttribute("currentPage", current);
        model.addAttribute("pageCount", pageCount);
        return items.subList((current - 1) * 9, Math.min(current * 9, items.size()));
    }

    @GetMapping("/packages/{id}/book")
    public String bookingForm(@PathVariable("id") Long packageId,
                             @AuthenticationPrincipal UserDetails userDetails,
                             Model model) {
        if (userDetails == null) {
            return "redirect:/auth/login";
        }
        return tourPackageService.findById(packageId).filter(p -> p.isActive() && p.getDestination().isActive()).map(pkg -> {
            model.addAttribute("requestToken", java.util.UUID.randomUUID().toString());
            model.addAttribute("tourPackage", pkg);
            model.addAttribute("hotels", hotelService.findByDestinationId(pkg.getDestination().getId()).stream().filter(h -> h.isActive()).toList());
            model.addAttribute("departures", departureRepository.findByTourPackage_Id(pkg.getId()).stream().filter(d -> d.getAvailableSeats() > 0 && d.getDepartureDate().isAfter(java.time.LocalDate.now())).toList());
            model.addAttribute("settings", settingsService.getSettings());
            return "customer/booking-form";
        }).orElse("redirect:/packages");
    }

    @GetMapping("/packages/{id}")
    public String packageDetail(@PathVariable Long id, Model model) {
        var pkg = tourPackageService.findById(id).filter(p -> p.isActive() && p.getDestination().isActive())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        model.addAttribute("tourPackage", pkg);
        model.addAttribute("settings", settingsService.getSettings());
        model.addAttribute("hotels", hotelService.findByDestinationId(pkg.getDestination().getId()).stream().filter(h -> h.isActive()).toList());
        model.addAttribute("relatedPackages", tourPackageService.findByDestinationId(pkg.getDestination().getId()).stream()
            .filter(p -> p.isActive() && !p.getId().equals(id)).limit(3).toList());
        return "package-detail";
    }

    @GetMapping("/visa-info")
    public String visaInfo(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        return "visa-info";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        return "about";
    }

    @GetMapping("/contact")
    public String contact(Model model) {
        model.addAttribute("settings",settingsService.getSettings());
        model.addAttribute("destinations",destinationService.findAll().stream().filter(d->d.isActive()).toList());
        model.addAttribute("packages",tourPackageService.findAll().stream().filter(p->p.isActive()&&p.getDestination().isActive()).toList());
        if(!model.containsAttribute("inquiry"))model.addAttribute("inquiry",new com.travelgo.dto.InquiryRequest("","","","",null,"GENERAL",null,null,null,null,"",java.util.UUID.randomUUID().toString()));
        return "contact";
    }
    @PostMapping("/contact")
    public String submitContact(@org.springframework.web.bind.annotation.ModelAttribute("inquiry") com.travelgo.dto.InquiryRequest inquiry,
            @RequestParam(value="website",required=false)String website,jakarta.servlet.http.HttpSession session,RedirectAttributes flash){
        try{
            synchronized(session){
                Long last=(Long)session.getAttribute("lastInquiryAt");long now=System.currentTimeMillis();
                if(website!=null&&!website.isBlank()||last!=null&&now-last<30000)throw new IllegalArgumentException("Please wait a moment before sending another inquiry.");
                var saved=contactMessageService.submit(inquiry);session.setAttribute("lastInquiryAt",now);
                flash.addFlashAttribute("successMessage","Inquiry "+saved.getReference()+" received. Our team will review your plans. Keep this reference for follow-up.");
            }
        }catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());flash.addFlashAttribute("inquiry",inquiry);}
         catch(Exception e){flash.addFlashAttribute("errorMessage","We could not save your inquiry. Please try again or contact the team.");flash.addFlashAttribute("inquiry",inquiry);}
        return "redirect:/contact";
    }

    @GetMapping("/faq")
    public String faq(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        return "faq";
    }

    @GetMapping("/maintenance")
    public String maintenance(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        return "maintenance";
    }
}
