package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

// @Component (Disabled to prevent test data generation)
public class Phase5Simulator implements CommandLineRunner {

    @Autowired
    private DestinationRepository destinationRepository;
    
    @Autowired
    private PackageCategoryRepository categoryRepository;

    @Autowired
    private TourPackageRepository tourPackageRepository;

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BookingRepository bookingRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=========================================");
        System.out.println("  STARTING PHASE 5 END-TO-END TEST");
        System.out.println("=========================================");

        try {
            transactionTemplate.execute(status -> {
                String timestamp = String.valueOf(System.currentTimeMillis());
                
                // 1. Admin creates a Destination and Category
                System.out.println("[TEST] Admin creating destination...");
                Destination dest = new Destination();
                dest.setCity("Tokyo Test " + timestamp);
                dest.setCountry("Japan");
                dest = destinationRepository.save(dest);

                PackageCategory cat = new PackageCategory();
                cat.setName("Adventure Test " + timestamp);
                cat = categoryRepository.save(cat);

                // 2. Admin creates a Package
                System.out.println("[TEST] Admin creating tour package...");
                TourPackage tour = new TourPackage();
                tour.setName("Phase 5 Tokyo " + timestamp);
                tour.setDestination(dest);
                tour.setCategory(cat);
                tour.setDurationDays(7);
                tour.setBasePrice(new BigDecimal("1500.00"));
                tour.setMaxCapacity(20);
                tour.setActive(true);
                tour.setFlightDetails("Direct Flight Tokyo-Air");
                TourPackage savedTour = tourPackageRepository.save(tour);
                
                System.out.println(" -> Package Created: ID " + savedTour.getId());

                // 3. Customer registers
                System.out.println("[TEST] Customer registering...");
                Role customerRole = roleRepository.findByRoleName("CUSTOMER").orElseThrow(() -> new RuntimeException("Role not found"));
                User customer = new User("Phase5 User " + timestamp, "phase5.test" + timestamp + "@example.com", "password123", customerRole);
                customer.setPhone("555-555-5555");
                customer.setActive(true);
                User savedCustomer = userRepository.save(customer);
                System.out.println(" -> Customer Registered: ID " + savedCustomer.getId());

                // 4. Customer Books the Package
                System.out.println("[TEST] Customer booking package...");
                Booking booking = new Booking();
                booking.setTourPackage(savedTour);
                booking.setUser(savedCustomer);
                booking.setNumberOfTravelers(2);
                booking.setTravelDate(LocalDate.now().plusDays(30));
                
                booking.setPackageUnitPrice(savedTour.getBasePrice());
                booking.setTotalPackageAmount(savedTour.getBasePrice().multiply(new BigDecimal(2)));
                booking.setBookingStatus(BookingStatus.PENDING);
                
                Booking savedBooking = bookingRepository.save(booking);
                System.out.println(" -> Booking Created: ID " + savedBooking.getId() + ", Status: " + savedBooking.getBookingStatus());
                
                // 5. Customer Pays Deposit
                System.out.println("[TEST] Customer paying deposit...");
                Payment deposit = new Payment();
                deposit.setBooking(savedBooking);
                deposit.setAmount(new BigDecimal("750.00"));
                deposit.setPaymentType(PaymentType.PACKAGE_DEPOSIT);
                deposit.setPaymentMethod("Credit Card");
                deposit.setTransactionReference("PHASE5-DEP-" + System.currentTimeMillis());
                deposit.setPaymentStatus(PaymentStatus.PAID);
                deposit.setPaidAt(java.time.LocalDateTime.now());
                
                paymentRepository.save(deposit);
                System.out.println(" -> Deposit Paid: " + deposit.getTransactionReference());

                System.out.println("[TEST] ALL PHASE 5 TESTS PASSED!");
                return null;
            });
        } catch (Exception e) {
            System.err.println("[TEST FAILED] Error during Phase 5 execution:");
            e.printStackTrace();
        }

        System.out.println("=========================================");
        System.out.println("  END OF PHASE 5 TEST");
        System.out.println("=========================================");
    }
}
