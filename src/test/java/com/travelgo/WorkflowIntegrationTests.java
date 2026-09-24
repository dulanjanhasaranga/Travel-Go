package com.travelgo;

import com.travelgo.dto.BookingRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=", "spring.jpa.open-in-view=true"})
@ActiveProfiles("test")
@Import(WorkflowIntegrationTests.Time.class)
public class WorkflowIntegrationTests {
    static class MutableClock extends Clock {
        volatile Instant instant=Instant.parse("2030-01-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant,zone); }
        public Instant instant() { return instant; }
    }
    @TestConfiguration static class Time { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    @Autowired MutableClock clock;
    @Autowired BookingService bookings;
    @Autowired VisaApplicationService visas;
    @Autowired VisaDocumentService documents;
    @Autowired PaymentService payments;
    @Autowired BookingRepository bookingRepo;
    @Autowired VisaApplicationRepository visaRepo;
    @Autowired VisaDocumentRepository documentRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired RefundRepository refundRepo;
    @Autowired VisaStatusHistoryRepository historyRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired TravelerRepository travelerRepo;
    @Autowired com.travelgo.service.ReviewService reviewService;
    @Autowired BookingHotelRepository bookingHotelRepo;
    @Autowired UserRepository userRepo;
    @Autowired RoleRepository roleRepo;
    @Autowired TourPackageRepository packageRepo;
    @Autowired DepartureRepository departureRepo;
    @Autowired DestinationRepository destinationRepo;
    @Autowired HotelRepository hotelRepo;
    @Autowired PackageCategoryRepository categoryRepo;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    TransactionTemplate tx;
    MockMvc mvc;
    User customer, other, officer, consultant, admin;
    TourPackage tour;
    Hotel hotel;
    @BeforeEach void fixture() {
        clock.instant=Instant.parse("2030-01-01T12:00:00Z"); tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx.executeWithoutResult(status -> {
            customer=account("CUSTOMER"); other=account("CUSTOMER"); officer=account("VISA_OFFICER"); consultant=account("TRAVEL_CONSULTANT"); admin=account("ADMIN");
            Destination d=new Destination(); d.setCountry("Test country"); d.setCity("Test city"); destinationRepo.save(d);
            PackageCategory c=new PackageCategory(); c.setName(UUID.randomUUID().toString()); categoryRepo.save(c);
            tour=new TourPackage(); tour.setName("Test tour"); tour.setDestination(d); tour.setCategory(c); tour.setBasePrice(new BigDecimal("100.00"));
            tour.setDurationDays(4); tour.setMaxCapacity(10); packageRepo.save(tour);
            
            Departure dep = new Departure();
            dep.setTourPackage(tour);
            dep.setDepartureDate(LocalDate.of(2030,2,1));
            dep.setReturnDate(LocalDate.of(2030,2,5));
            dep.setTotalCapacity(10);
            dep.setAvailableSeats(10);
            departureRepo.save(dep);
            
            Departure dep2 = new Departure();
            dep2.setTourPackage(tour);
            dep2.setDepartureDate(LocalDate.of(2030,3,1));
            dep2.setReturnDate(LocalDate.of(2030,3,5));
            dep2.setTotalCapacity(10);
            dep2.setAvailableSeats(10);
            departureRepo.save(dep2);
            
            hotel=new Hotel(); hotel.setDestination(d); hotel.setName("Test hotel"); hotel.setAddress("Test address"); hotel.setPricePerNight(new BigDecimal("20.00")); hotelRepo.save(hotel);
        });
        login(customer);
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    User account(String roleName) {
        Role role=roleRepo.findByRoleName(roleName).orElseGet(() -> { Role r=new Role(); r.setRoleName(roleName); return roleRepo.save(r); });
        User user=new User("Test user",UUID.randomUUID()+"@example.invalid","unused-test-password",role); return userRepo.save(user);
    }
    void login(User u) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getEmail(),"unused",authorities(u.getRole().getRoleName()))); }
    /** Build ROLE_* + PERM_* authorities matching DataInitializer.assignStaffPermissions(). */
    static List<SimpleGrantedAuthority> authorities(String roleName) {
        List<String> auths = new java.util.ArrayList<>();
        auths.add("ROLE_" + roleName);
        switch (roleName) {
            case "TRAVEL_CONSULTANT" -> auths.addAll(List.of("PERM_DESTINATION_VIEW","PERM_DESTINATION_MANAGE","PERM_PACKAGE_VIEW","PERM_PACKAGE_MANAGE","PERM_BOOKING_VIEW","PERM_BOOKING_MANAGE"));
            case "VISA_OFFICER" -> auths.addAll(List.of("PERM_VISA_VIEW","PERM_VISA_MANAGE","PERM_PAYMENT_VIEW","PERM_PAYMENT_MANAGE"));
            case "ADMIN" -> auths.addAll(List.of("PERM_USER_VIEW","PERM_USER_MANAGE","PERM_STAFF_VIEW","PERM_STAFF_MANAGE","PERM_ROLE_VIEW","PERM_ROLE_MANAGE","PERM_PERMISSION_VIEW","PERM_SYSTEM_SETTINGS_VIEW","PERM_SYSTEM_SETTINGS_MANAGE","PERM_DASHBOARD_VIEW","PERM_PACKAGE_VIEW","PERM_PACKAGE_MANAGE","PERM_DESTINATION_VIEW","PERM_DESTINATION_MANAGE","PERM_VISA_VIEW","PERM_VISA_MANAGE","PERM_BOOKING_VIEW","PERM_BOOKING_MANAGE","PERM_PAYMENT_VIEW","PERM_PAYMENT_MANAGE"));
            default -> {} // CUSTOMER needs no PERM_*
        }
        return auths.stream().map(SimpleGrantedAuthority::new).toList();
    }
    static org.springframework.test.web.servlet.request.RequestPostProcessor staffUser(String email, String roleName) {
        return request -> {
            org.springframework.security.core.context.SecurityContext context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            var principal = new org.springframework.security.core.userdetails.User(email, "unused", authorities(roleName));
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "unused", authorities(roleName)));
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext(context).postProcessRequest(request);
            return request;
        };
    }
    BookingRequest.TravelerInput traveler() { return new BookingRequest.TravelerInput("Traveler", "P123",LocalDate.of(1990,1,1),"Other","Test"); }
    Booking create(boolean withHotel) { login(customer); return bookings.createBooking(new BookingRequest(tour.getId(),LocalDate.of(2030,2,1),1,withHotel?hotel.getId():null,List.of(traveler()))); }
    VisaApplication apply(Booking b) { login(customer); return visas.createVisaApplication(b,VisaType.values()[0]); }
    MockMultipartFile pdf() { return new MockMultipartFile("file","passport.pdf","application/pdf","%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII)); }
    VisaApplication quote(Booking b) throws Exception {
        VisaApplication v=apply(b); documents.upload(v.getId(),"Passport",pdf()); login(officer);
        visas.markDocumentsVerified(v.getId()); return visas.setVisaCharges(v.getId(),new BigDecimal("30.00"),new BigDecimal("10.00"));
    }
    Payment pay(Booking b,PaymentType type,String amount) {
        Payment p=new Payment(); p.setBooking(b); p.setPaymentType(type); p.setAmount(new BigDecimal(amount)); p.setPaymentMethod("Simulation"); return payments.processPayment(p);
    }
    VisaApplication processing(Booking b) throws Exception { VisaApplication v=quote(b); login(customer); pay(b,PaymentType.VISA_DOCUMENTATION,"40.00"); return v; }
    @Test void bookingAndHotelAreAtomicAndConsistent() {
        long before=bookingRepo.count(), travelerCount=travelerRepo.count();
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(new BookingRequest(tour.getId(),LocalDate.of(2030,2,1),1,Long.MAX_VALUE,List.of(traveler()))));
        assertEquals(before,bookingRepo.count()); assertEquals(travelerCount,travelerRepo.count());
        Booking b=create(true); BookingHotel bh=bookingHotelRepo.findByBooking_Id(b.getId()).orElseThrow();
        assertEquals(3,bh.getNumberOfNights()); assertEquals(bh.getCheckInDate().plusDays(3),bh.getCheckOutDate()); assertEquals(0,bh.getHotelCost().compareTo(new BigDecimal("60")));
    }
    @Test void invalidBookingInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(new BookingRequest(tour.getId(),LocalDate.of(2030,1,4),1,null,List.of(traveler()))));
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(new BookingRequest(tour.getId(),LocalDate.of(2030,2,1),2,null,List.of(traveler()))));
        assertThrows(IllegalArgumentException.class, () -> BookingRequest.travelers(List.of("A"),List.of(),List.of(),List.of(),List.of()));
        tx.executeWithoutResult(s -> packageRepo.findById(tour.getId()).orElseThrow().setActive(false));
        assertThrows(IllegalArgumentException.class, () -> create(false));
    }
    @Test void modificationsReplaceTravelersRejectStaleFormsAndLockAfterReview() throws Exception {
        Booking b=create(true); LocalDateTime stamp=bookingRepo.findById(b.getId()).orElseThrow().getUpdatedAt();
        bookings.modifyBooking(b.getId(),LocalDate.of(2030,3,1),3,List.of(traveler(),traveler(),traveler()),stamp);
        assertEquals(3,travelerRepo.findByBooking_Id(b.getId()).size());
        BookingHotel h=bookingHotelRepo.findByBooking_Id(b.getId()).orElseThrow(); assertEquals(2,h.getNumberOfRooms()); assertEquals(LocalDate.of(2030,3,4),h.getCheckOutDate());
        assertThrows(IllegalStateException.class,() -> bookings.modifyBooking(b.getId(),null,1,List.of(traveler()),stamp));
        VisaApplication v=apply(b); documents.upload(v.getId(),"Passport",pdf()); login(officer); visas.markDocumentsVerified(v.getId()); login(customer);
        assertThrows(IllegalStateException.class,() -> bookings.cancelBooking(b.getId()));
        assertThrows(IllegalStateException.class,() -> documents.upload(v.getId(),"More",pdf()));
    }
    @Test void customerCannotMutateAnotherCustomersBooking() {
        Booking b=create(false); login(other);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,() -> bookings.cancelBooking(b.getId()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,() -> visas.createVisaApplication(b,VisaType.values()[0]));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,() -> pay(b,PaymentType.FULL_PACKAGE,"100"));
    }
    
    @Test void backendAcceptsOnlyValidReviewsForCompletedTrips() {
        Booking b = create(false); // pending booking
        
        com.travelgo.entity.Review review1 = new com.travelgo.entity.Review();
        review1.setBooking(b); review1.setRating(5);
        assertThrows(IllegalArgumentException.class, () -> reviewService.save(review1));
        
        // Fast forward booking to completed
        login(consultant);
        tx.executeWithoutResult(s -> bookingRepo.findById(b.getId()).orElseThrow().setBookingStatus(com.travelgo.enums.BookingStatus.CONFIRMED));
        login(customer);
        clock.instant = clock.instant.plus(java.time.Duration.ofDays(b.getTourPackage().getDurationDays() + 1)); // After trip ends
        
        // Test rating limits
        com.travelgo.entity.Review review2 = new com.travelgo.entity.Review();
        review2.setBooking(b); review2.setRating(99);
        assertThrows(IllegalArgumentException.class, () -> reviewService.save(review2));
        
        com.travelgo.entity.Review review3 = new com.travelgo.entity.Review();
        review3.setBooking(b); review3.setRating(0);
        assertThrows(IllegalArgumentException.class, () -> reviewService.save(review3));
        
        // Test successful review
        com.travelgo.entity.Review review4 = new com.travelgo.entity.Review();
        review4.setBooking(b); review4.setRating(5); review4.setComment("Great trip!");
        reviewService.save(review4);
        
        // Test only one review allowed
        com.travelgo.entity.Review review5 = new com.travelgo.entity.Review();
        review5.setBooking(b); review5.setRating(4);
        assertThrows(IllegalArgumentException.class, () -> reviewService.save(review5));
    }
    
    @Test void documentsRequireOwnerAndValidContentAndPreserveLegacyPaths() throws Exception {
        Booking b=create(false); VisaApplication v=apply(b);
        assertThrows(IllegalArgumentException.class,() -> documents.upload(v.getId(),"Bad",new MockMultipartFile("file","image.png","image/png","not an image".getBytes())));
        assertThrows(IllegalArgumentException.class,() -> documents.upload(v.getId(),"Bad",new MockMultipartFile("file","bad.html","text/html","<script/>".getBytes())));
        var doc=documents.upload(v.getId(),"Passport",pdf()); assertArrayEquals(pdf().getBytes(),documents.download(doc.getId()).content());
        login(other); assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> documents.download(doc.getId()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,() -> documents.upload(v.getId(),"Passport",pdf()));
        login(officer); assertNotNull(documents.download(doc.getId())); login(consultant); assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> documents.download(doc.getId()));
        login(customer);
        Path legacy=Path.of("target/test-uploads/legacy-"+UUID.randomUUID()+".pdf"); Files.createDirectories(legacy.getParent()); Files.write(legacy,pdf().getBytes());
        tx.executeWithoutResult(s -> documentRepo.findById(doc.getId()).orElseThrow().setDocumentUrl(legacy.toString()));
        assertArrayEquals(pdf().getBytes(),documents.download(doc.getId()).content());
        tx.executeWithoutResult(s -> documentRepo.findById(doc.getId()).orElseThrow().setDocumentUrl("pom.xml"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> documents.download(doc.getId()));
    }
    @Test void uploadRollbackRemovesNewFile() throws Exception {
        Booking b=create(false); VisaApplication v=apply(b); Path dir=Path.of("target/test-uploads"); Files.createDirectories(dir);
        long before; try(var files=Files.list(dir)) { before=files.count(); }
        tx.executeWithoutResult(status -> { try { documents.upload(v.getId(),"Passport",pdf()); } catch(Exception e) { throw new RuntimeException(e); } status.setRollbackOnly(); });
        try(var files=Files.list(dir)) { assertEquals(before,files.count()); }
        assertTrue(documentRepo.findByVisaApplication_Id(v.getId()).isEmpty());
    }
    @Test void visaCannotSkipVerificationOrProcessing() {
        Booking b=create(false); VisaApplication v=apply(b); login(officer);
        assertThrows(IllegalStateException.class,() -> visas.markDocumentsVerified(v.getId()));
        assertThrows(IllegalStateException.class,() -> visas.approveVisa(v.getId()));
        assertThrows(IllegalStateException.class,() -> visas.setVisaCharges(v.getId(),BigDecimal.TEN,BigDecimal.ONE));
        assertThrows(IllegalStateException.class,() -> visas.startProcessing(v.getId()));
    }
    @Test void quoteTamperingAndDeadlineBoundaryCannotBeBypassedByStaff() throws Exception {
        Booking b=create(false); VisaApplication v=quote(b); login(customer);
        assertThrows(IllegalArgumentException.class,() -> pay(b,PaymentType.VISA_DOCUMENTATION,"0.01"));
        assertThrows(IllegalArgumentException.class,() -> pay(b,PaymentType.VISA_DOCUMENTATION,"-1"));
        clock.instant=v.getPaymentExpiresAt().toInstant(ZoneOffset.UTC);
        assertThrows(IllegalStateException.class,() -> pay(b,PaymentType.VISA_DOCUMENTATION,"40"));
        login(officer);
        assertThrows(IllegalStateException.class,() -> payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Manual",null,PaymentStatus.PAID,null,null));
        visas.reissueCharges(v.getId()); login(customer); pay(b,PaymentType.VISA_DOCUMENTATION,"40");
        assertEquals(VisaStatus.PROCESSING,visaRepo.findById(v.getId()).orElseThrow().getStatus());
        login(officer); assertThrows(IllegalStateException.class,() -> visas.setVisaCharges(v.getId(),BigDecimal.ONE,BigDecimal.ONE));
    }
    @Test void finalPaymentRequiresVisaAndIncludesHotel() throws Exception {
        Booking b=create(true); login(customer); assertThrows(IllegalStateException.class,() -> pay(b,PaymentType.FULL_PACKAGE,"160"));
        VisaApplication v=processing(b); login(consultant); assertThrows(IllegalStateException.class,() -> bookings.confirmBooking(b.getId()));
        login(officer); visas.approveVisa(v.getId()); login(customer);
        assertThrows(IllegalArgumentException.class,() -> pay(b,PaymentType.FULL_PACKAGE,"100"));
        pay(b,PaymentType.FULL_PACKAGE,"160"); assertEquals(BookingStatus.CONFIRMED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
    }
    @Test void finalDeadlineAndManualStatusTransitionsAreEnforced() throws Exception {
        Booking b=create(false); VisaApplication v=processing(b); login(officer); visas.approveVisa(v.getId());
        Payment pending=payments.recordManualPayment(b.getId(),PaymentType.FULL_PACKAGE,new BigDecimal("100"),"Manual",null,PaymentStatus.PENDING,null,null);
        clock.instant=b.getPackagePaymentDeadline().toInstant(ZoneOffset.UTC);
        assertThrows(IllegalStateException.class,() -> payments.updatePaymentStatus(pending.getId(),PaymentStatus.PAID,null,null));
        payments.updatePaymentStatus(pending.getId(),PaymentStatus.EXPIRED,null,null);
        assertThrows(IllegalStateException.class,() -> payments.updatePaymentStatus(pending.getId(),PaymentStatus.PAID,null,null));
    }
    @Test void successfulRetriesAndRejectionsAreIdempotent() throws Exception {
        Booking b=create(false); VisaApplication v=processing(b); login(customer);
        long history=historyRepo.count(), notices=notificationRepo.count();
        Payment paid=pay(b,PaymentType.VISA_DOCUMENTATION,"40"); pay(b,PaymentType.VISA_DOCUMENTATION,"40");
        assertEquals(1,paymentRepo.findByBooking_Id(b.getId()).size()); assertEquals(history,historyRepo.count()); assertEquals(notices,notificationRepo.count());
        login(officer); assertThrows(IllegalStateException.class,() -> payments.updatePaymentStatus(paid.getId(),PaymentStatus.FAILED,null,null));
        assertThrows(IllegalArgumentException.class,() -> visas.rejectVisa(v.getId(),"",null));
        BigDecimal before=payments.getTotalRevenue(); visas.rejectVisa(v.getId(),"Embassy declined",null); long afterHistory=historyRepo.count();
        visas.rejectVisa(v.getId(),"Repeated",null); assertEquals(afterHistory,historyRepo.count());
        Refund r=refundRepo.findByPayment_Id(paid.getId()).orElseThrow(); assertEquals(0,r.getAmount().compareTo(BigDecimal.TEN));
        assertEquals(PaymentStatus.PAID,paymentRepo.findById(paid.getId()).orElseThrow().getPaymentStatus());
        assertEquals(0,before.subtract(BigDecimal.TEN).compareTo(payments.getTotalRevenue()));
        assertEquals(BookingStatus.CANCELLED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
    }
    @Test void concurrentPaymentSubmissionsCreateOneRecord() throws Exception {
        Booking b=create(false); quote(b); var executor=Executors.newFixedThreadPool(2); CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        Callable<Long> work=() -> { login(customer); ready.countDown(); try { go.await(); return pay(b,PaymentType.VISA_DOCUMENTATION,"40").getId(); } finally { SecurityContextHolder.clearContext(); } };
        try { Future<Long> a=executor.submit(work),c=executor.submit(work); assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown(); assertEquals(a.get(15,TimeUnit.SECONDS),c.get(15,TimeUnit.SECONDS)); }
        finally { go.countDown(); executor.shutdownNow(); }
        assertEquals(1,paymentRepo.findByBooking_Id(b.getId()).size());
    }
    @Test void mvcRoleMatrixCsrfAndDocumentResponses() throws Exception {
        Booking b=create(false); VisaApplication v=apply(b); VisaDocument d=documents.upload(v.getId(),"Passport",pdf());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/visa-documents/"+d.getId()+"/download")).andExpect(status().isNotFound());
        mvc.perform(get("/visa-documents/"+d.getId()+"/download").with(user(other.getEmail()).roles("CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(get("/visa-documents/"+d.getId()+"/download").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/uploads/visa-documents/test.pdf").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(post("/staff/visas/"+v.getId()+"/verify-all").with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/staff/bookings/"+b.getId()+"/confirm").with(staffUser(officer.getEmail(),"VISA_OFFICER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/customer/bookings/"+b.getId()+"/cancel").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
    }
    @Test void customerAndStaffPagesRenderAtEachWorkflowStage() throws Exception {
        Booking b=create(true);
        SecurityContextHolder.clearContext();
        mvc.perform(get("/customer/bookings/"+b.getId()).with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(view().name("customer/booking-detail"));
        mvc.perform(get("/customer/payments/checkout/"+b.getId()).with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(view().name("customer/payment-checkout"));
        VisaApplication v=quote(b); SecurityContextHolder.clearContext();
        mvc.perform(get("/customer/bookings/"+b.getId()).with(user(customer.getEmail()).roles("CUSTOMER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Proceed to Checkout")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"expectedUpdatedAt\""))));
        mvc.perform(get("/customer/visas").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(view().name("customer/visas"));
        mvc.perform(get("/customer/payments/checkout/"+b.getId()).with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(view().name("customer/payment-checkout"));
        mvc.perform(get("/staff/visas").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk()).andExpect(view().name("staff/visas"));
        mvc.perform(get("/staff/payments").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk()).andExpect(view().name("staff/payments"));
        mvc.perform(get("/staff/dashboard").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk()).andExpect(view().name("staff/dashboard"));
        mvc.perform(get("/staff/dashboard").with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isOk()).andExpect(view().name("staff/dashboard"));
    }
    @Test void staffVisaPageOffersVerificationOnlyAfterDocumentsAreSubmitted() throws Exception {
        Booking b=create(false); VisaApplication v=apply(b);
        String verifyAllPath="/staff/visas/"+v.getId()+"/verify-all";
        mvc.perform(get("/staff/visas").with(staffUser(officer.getEmail(),"VISA_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(verifyAllPath))));
        login(customer); VisaDocument document=documents.upload(v.getId(),"Passport",pdf());
        mvc.perform(get("/staff/visas").with(staffUser(officer.getEmail(),"VISA_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(verifyAllPath)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/staff/visas/documents/"+document.getId()+"/verify")));
    }
    @Test void oversizedAndMismatchedFilesAreRejectedAndImagesAccepted() throws Exception {
        Booking b=create(false); VisaApplication v=apply(b);
        assertThrows(IllegalArgumentException.class, () -> documents.upload(v.getId(),"Huge",new MockMultipartFile("file","huge.pdf","application/pdf",new byte[10*1024*1024+1])));
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        byte[] png=output.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> documents.upload(v.getId(),"Mismatch",new MockMultipartFile("file","renamed.jpg","image/jpeg",png)));
        VisaDocument doc=documents.upload(v.getId(),"Image",new MockMultipartFile("file","../../passport.png","application/octet-stream",png));
        assertFalse(doc.getDocumentUrl().contains("..")); assertArrayEquals(png,documents.download(doc.getId()).content());
    }
    @Test void staffRoleMatrixCoversEveryRestrictedSection() throws Exception {
        SecurityContextHolder.clearContext();
        for (String path : List.of("/staff/packages","/staff/destinations","/staff/hotels","/staff/categories","/staff/inquiries","/staff/bookings")) {
            mvc.perform(get(path).with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isForbidden());
        }
        for (String path : List.of("/staff/visas","/staff/payments")) {
            mvc.perform(get(path).with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isForbidden());
        }
    }
    @Test void concurrentRejectionCreatesOneRefundAndOneDecision() throws Exception {
        Booking b=create(false); VisaApplication v=processing(b); long initial=historyRepo.count();
        var executor=Executors.newFixedThreadPool(2); CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        Callable<Long> work=() -> { login(officer); ready.countDown(); try { go.await(); return visas.rejectVisa(v.getId(),"Embassy declined",null).getId(); } finally { SecurityContextHolder.clearContext(); } };
        try { Future<Long> a=executor.submit(work),c=executor.submit(work); assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown(); assertEquals(a.get(15,TimeUnit.SECONDS),c.get(15,TimeUnit.SECONDS)); }
        finally { go.countDown(); executor.shutdownNow(); }
        assertEquals(initial+1,historyRepo.count());
        Payment p=paymentRepo.findByBooking_Id(b.getId()).get(0); assertTrue(refundRepo.findByPayment_Id(p.getId()).isPresent());
    }
}
