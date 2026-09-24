package com.travelgo;

import com.travelgo.dto.PackageRequest;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

public class PackagePersistenceIT extends WorkflowIntegrationTests {
    @Autowired TourPackageService catalogue;
    @Autowired OriginalCatalogueService originals;
    PackageRequest request(String name, String price, int days) {
        return new PackageRequest(name,tour.getCategory().getId(),tour.getDestination().getId(),"Test description",
                new BigDecimal(price),days,"Not included","Guided walk",12,"/images/paris.jpg", null, null, null, null);
    }
    @Test void staffCrudReachesDatabaseAndPublicPagesAndPreservesBooking() throws Exception {
        String name="Persistence package "+UUID.randomUUID();
        SecurityContextHolder.clearContext();
        mvc.perform(post("/staff/packages/create").with(user(consultant.getEmail()).roles("TRAVEL_CONSULTANT")).with(csrf())
                .param("name",name).param("categoryId",tour.getCategory().getId().toString())
                .param("destinationId",tour.getDestination().getId().toString()).param("basePrice","125.50")
                .param("durationDays","4").param("maxCapacity","12"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        var p=packageRepo.findByNameContainingIgnoreCase(name).get(0);
        mvc.perform(get("/packages/"+p.getId())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(name)));
        login(customer);
        var b=bookings.createBooking(new com.travelgo.dto.BookingRequest(p.getId(),java.time.LocalDate.of(2030,2,1),1,null,java.util.List.of(traveler())));
        login(consultant); catalogue.savePackage(p.getId(),request(name+" updated","175.00",4));
        assertEquals(0,bookingRepo.findById(b.getId()).orElseThrow().getPackageUnitPrice().compareTo(new BigDecimal("125.50")));
        assertEquals(0,packageRepo.findById(p.getId()).orElseThrow().getBasePrice().compareTo(new BigDecimal("175.00")));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/packages/"+p.getId())).andExpect(content().string(org.hamcrest.Matchers.containsString(name+" updated")));
        mvc.perform(post("/staff/packages/"+p.getId()+"/delete").with(user(consultant.getEmail()).roles("TRAVEL_CONSULTANT")).with(csrf()))
                .andExpect(flash().attributeExists("successMessage"));
        assertFalse(packageRepo.findById(p.getId()).orElseThrow().isActive());
        assertTrue(bookingRepo.existsById(b.getId()));
        mvc.perform(get("/packages/"+p.getId())).andExpect(status().isNotFound());
        login(consultant); catalogue.updateContent(p.getId(),"Day 1: Explore","Flights","Families",true);
        SecurityContextHolder.clearContext(); mvc.perform(get("/packages/"+p.getId())).andExpect(status().isOk());
    }
    @Test void invalidOrUnauthorizedPackageChangesDoNotPersist() {
        long before=packageRepo.count(); login(consultant);
        assertThrows(IllegalArgumentException.class,()->catalogue.savePackage(null,request("Invalid","-1",4)));
        assertThrows(IllegalArgumentException.class,()->catalogue.savePackage(null,request("Invalid","10",0)));
        login(customer); assertThrows(org.springframework.security.access.AccessDeniedException.class,()->catalogue.savePackage(null,request("Denied","10",4)));
        assertEquals(before,packageRepo.count());
    }
    @Test void bookedPackageCannotChangeDestinationOrDuration() {
        create(true); login(consultant);
        assertThrows(IllegalStateException.class,()->catalogue.savePackage(tour.getId(),request("Changed","100",9)));
        assertEquals(tour.getDurationDays(),packageRepo.findById(tour.getId()).orElseThrow().getDurationDays());
    }
    @Test void originalCatalogueIsRecoverableWithExistingPackagesAndDoesNotOverwriteChanges() {
        originals.install(); var p=packageRepo.findByNameContainingIgnoreCase("European Classical Journey").get(0);
        tx.executeWithoutResult(s->{var stored=packageRepo.findById(p.getId()).orElseThrow();stored.setActive(false);stored.setBasePrice(new BigDecimal("2222.00"));});
        long packages=packageRepo.count(),hotels=hotelRepo.count(); originals.install();
        assertEquals(packages,packageRepo.count()); assertEquals(hotels,hotelRepo.count());
        var retained=packageRepo.findById(p.getId()).orElseThrow(); assertFalse(retained.isActive());
        assertEquals(0,retained.getBasePrice().compareTo(new BigDecimal("2222.00")));
    }
}

