package com.travelgo;

import com.travelgo.dto.BookingRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Committed synthetic fixtures in a dedicated in-memory database; no SMTP or provider calls. */
@SpringBootTest(properties={"spring.config.import=",
    "spring.datasource.url=jdbc:h2:mem:travelgo-visa-refund-notices;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "travelgo.upload-directory=target/test-visa-refund-notice-uploads",
    "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false",
    "travelgo.payments.simulated=true"})
@ActiveProfiles("test")
@Import({VisaDocumentRefundNotificationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class VisaDocumentRefundNotificationTests {
    @TestConfiguration static class Time {
        @Bean @Primary Clock notificationWorkflowClock() {
            return Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"),ZoneOffset.UTC);
        }
    }
    @Autowired BookingService bookings;
    @Autowired VisaApplicationService visas;
    @Autowired VisaDocumentService documents;
    @Autowired PaymentService payments;
    @Autowired NotificationService inbox;
    @Autowired BookingRepository bookingRepo;
    @Autowired VisaApplicationRepository visaRepo;
    @Autowired VisaDocumentRepository documentRepo;
    @Autowired VisaStatusHistoryRepository historyRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired RefundRepository refundRepo;
    @Autowired NotificationRepository notices;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired DepartureRepository departures;
    @Autowired SystemSettingsRepository settings;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx;
    User customer, other, officer, secondOfficer, inactiveOfficer, consultant, admin;
    Booking booking;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        if(settings.count()==0)settings.saveAndFlush(new SystemSettings());
        customer=account("CUSTOMER"); other=account("CUSTOMER");
        officer=account("VISA_OFFICER"); secondOfficer=account("VISA_OFFICER"); inactiveOfficer=account("VISA_OFFICER");
        inactiveOfficer.setActive(false);users.saveAndFlush(inactiveOfficer);
        consultant=account("TRAVEL_CONSULTANT");admin=account("ADMIN");
        var destination=new Destination();destination.setCity("TEST notification city");destination.setCountry("TEST country");destinations.save(destination);
        var category=new PackageCategory();category.setName("TEST notification "+UUID.randomUUID());categories.save(category);
        var tour=new TourPackage();tour.setName("TEST notification tour");tour.setCategory(category);tour.setDestination(destination);
        tour.setBasePrice(new BigDecimal("100.00"));tour.setDurationDays(4);tour.setMaxCapacity(10);packages.save(tour);
        var departure=new Departure();departure.setTourPackage(tour);departure.setDepartureDate(LocalDate.of(2030,2,1));
        departure.setReturnDate(LocalDate.of(2030,2,5));departure.setTotalCapacity(10);departure.setAvailableSeats(10);departures.save(departure);
        login(customer);
        booking=bookings.createBooking(new BookingRequest(tour.getId(),LocalDate.of(2030,2,1),1,null,
            List.of(new BookingRequest.TravelerInput("TEST traveler","P123",LocalDate.of(1990,1,1),"Other","TEST"))));
    }
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    User account(String roleName){
        Role role=roles.findByRoleName(roleName).orElseGet(()->roles.save(new Role(roleName,"TEST notification role")));
        return users.save(new User("TEST notification user",UUID.randomUUID()+"@example.invalid","unused-test-password",role));
    }
    void login(User user){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(),"unused",
            List.of(new SimpleGrantedAuthority("ROLE_"+user.getRole().getRoleName()))));
    }
    MockMultipartFile pdf(){return new MockMultipartFile("file","passport.pdf","application/pdf",
        "%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII));}
    List<Notification> events(User user,String type,Long entityId){
        return notices.findByUser_IdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(n->type.equals(n.getType())&&entityId.equals(n.getRelatedEntityId())).toList();
    }
    VisaApplication apply(){login(customer);return visas.createVisaApplication(booking,VisaType.VISIT);}
    VisaApplication processing(String method,BigDecimal documentationCharge)throws Exception{
        var visa=apply();documents.upload(visa.getId(),"TEST passport",pdf());login(officer);
        visas.markDocumentsVerified(visa.getId());visas.setVisaCharges(visa.getId(),new BigDecimal("30.00"),documentationCharge);
        BigDecimal amount=new BigDecimal("30.00").add(documentationCharge);
        if(method.equals("Simulation")){
            login(customer);var payment=new Payment();payment.setBooking(booking);payment.setPaymentType(PaymentType.VISA_DOCUMENTATION);
            payment.setAmount(amount);payment.setPaymentMethod(method);payments.processPayment(payment);
        }else{
            payments.recordManualPayment(booking.getId(),PaymentType.VISA_DOCUMENTATION,amount,method,
                "TEST-BANK-"+UUID.randomUUID(),PaymentStatus.PAID,null,"TEST verified receipt fixture.");
        }
        login(officer);return visa;
    }

    @Test void everyAddedDocumentNotifiesActiveOfficersWithoutRepeatingStatusHistory()throws Exception{
        var visa=apply();documents.upload(visa.getId(),"TEST passport",pdf());
        assertEquals(1,events(officer,"VISA_SUBMITTED",visa.getId()).size());
        assertEquals(1,events(secondOfficer,"VISA_SUBMITTED",visa.getId()).size());
        long history=historyRepo.findByVisaApplication_IdOrderByChangedAtAsc(visa.getId()).size();
        documents.upload(visa.getId(),"TEST financial statement",pdf());
        documents.upload(visa.getId(),"TEST accommodation evidence",pdf());
        assertEquals(3,events(officer,"VISA_SUBMITTED",visa.getId()).size());
        assertEquals(3,events(secondOfficer,"VISA_SUBMITTED",visa.getId()).size());
        for(var excluded:List.of(inactiveOfficer,consultant,admin,other))
            assertTrue(events(excluded,"VISA_SUBMITTED",visa.getId()).isEmpty());
        assertEquals(1,events(customer,"VISA_DOCUMENTS_SUBMITTED",visa.getId()).size());
        assertEquals(history,historyRepo.findByVisaApplication_IdOrderByChangedAtAsc(visa.getId()).size());
        assertEquals(VisaStatus.DOCUMENTS_SUBMITTED,visaRepo.findById(visa.getId()).orElseThrow().getStatus());
        login(officer);
        assertTrue(inbox.inbox("visa",false,0).stream().anyMatch(n->"New visa document uploaded".equals(n.title())&&"/staff/visas".equals(n.url())));
    }

    @Test void uploadRespondingToDocumentRequestUsesOneExistingTransitionNotice()throws Exception{
        var visa=apply();documents.upload(visa.getId(),"TEST passport",pdf());login(officer);
        visas.requestDocuments(visa.getId(),"TEST clearer financial statement");
        long history=historyRepo.findByVisaApplication_IdOrderByChangedAtAsc(visa.getId()).size();
        login(customer);documents.upload(visa.getId(),"TEST requested statement",pdf());
        assertEquals(2,events(officer,"VISA_SUBMITTED",visa.getId()).size());
        assertEquals(history+1,historyRepo.findByVisaApplication_IdOrderByChangedAtAsc(visa.getId()).size());
    }

    @Test void failedAndRolledBackUploadsDoNotCreateOfficerNotices()throws Exception{
        var visa=apply();documents.upload(visa.getId(),"TEST passport",pdf());
        assertThrows(IllegalArgumentException.class,()->documents.upload(visa.getId(),"Invalid image",
            new MockMultipartFile("file","invalid.png","image/png","invalid".getBytes(StandardCharsets.US_ASCII))));
        login(other);assertThrows(AccessDeniedException.class,()->documents.upload(visa.getId(),"Foreign document",pdf()));login(customer);
        Path directory=Path.of("target/test-visa-refund-notice-uploads");
        long filesBefore;try(var files=Files.list(directory)){filesBefore=files.count();}
        tx.executeWithoutResult(status->{
            try{documents.upload(visa.getId(),"Rolled back document",pdf());}catch(Exception e){throw new RuntimeException(e);}
            status.setRollbackOnly();
        });
        assertEquals(1,events(officer,"VISA_SUBMITTED",visa.getId()).size());
        assertEquals(1,documentRepo.findByVisaApplication_Id(visa.getId()).size());
        try(var files=Files.list(directory)){assertEquals(filesBefore,files.count());}
    }

    @Test void simulatedRejectionRefundNotifiesCustomerOnceWithDocumentationAmount()throws Exception{
        var visa=processing("Simulation",new BigDecimal("10.00"));
        visas.rejectVisa(visa.getId(),"TEST rejection",null);visas.rejectVisa(visa.getId(),"TEST repeated rejection",null);
        var payment=paymentRepo.findByBooking_Id(booking.getId()).get(0);var refund=refundRepo.findByPayment_Id(payment.getId()).orElseThrow();
        assertEquals(RefundStatus.REFUND_PROCESSED,refund.getStatus());assertNotNull(refund.getProcessedAt());
        assertEquals(0,new BigDecimal("10.00").compareTo(refund.getAmount()));
        assertEquals(PaymentStatus.PAID,payment.getPaymentStatus());assertEquals(0,new BigDecimal("40.00").compareTo(payment.getAmount()));
        var own=events(customer,"PAYMENT_REFUND_RECORDED",booking.getId());assertEquals(1,own.size());
        assertTrue(own.get(0).getMessage().contains("$10.00"));assertTrue(own.get(0).getMessage().contains("No real money was transferred."));
        assertTrue(events(other,"PAYMENT_REFUND_RECORDED",booking.getId()).isEmpty());
        login(customer);
        assertTrue(inbox.inbox("payment",false,0).stream().anyMatch(n->n.id().equals(own.get(0).getId())&&n.url().equals("/customer/bookings/"+booking.getId())));
    }

    @Test void realPaymentRejectionCreatesOnePendingNoticeWithoutClaimingSettlement()throws Exception{
        var visa=processing("Bank Transfer",new BigDecimal("10.00"));
        visas.rejectVisa(visa.getId(),"TEST rejection",null);visas.rejectVisa(visa.getId(),"TEST retry",null);
        var payment=paymentRepo.findByBooking_Id(booking.getId()).get(0);var refund=refundRepo.findByPayment_Id(payment.getId()).orElseThrow();
        assertEquals(RefundStatus.REFUND_PENDING,refund.getStatus());assertNull(refund.getProcessedAt());
        assertEquals(PaymentStatus.PAID,payment.getPaymentStatus());
        var own=events(customer,"PAYMENT_REFUND_PENDING",booking.getId());assertEquals(1,own.size());
        assertEquals("Documentation refund pending",own.get(0).getTitle());assertTrue(own.get(0).getMessage().contains("$10.00"));
        assertTrue(events(customer,"PAYMENT_REFUND_RECORDED",booking.getId()).isEmpty());
    }

    @Test void rejectedTransactionRollsBackRefundAndCustomerNotice()throws Exception{
        var visa=processing("Simulation",new BigDecimal("10.00"));
        tx.executeWithoutResult(status->{visas.rejectVisa(visa.getId(),"TEST rolled-back rejection",null);status.setRollbackOnly();});
        var payment=paymentRepo.findByBooking_Id(booking.getId()).get(0);
        assertTrue(refundRepo.findByPayment_Id(payment.getId()).isEmpty());
        assertTrue(events(customer,"PAYMENT_REFUND_RECORDED",booking.getId()).isEmpty());
        assertEquals(VisaStatus.PROCESSING,visaRepo.findById(visa.getId()).orElseThrow().getStatus());
        assertNotEquals(BookingStatus.CANCELLED,bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
    }

    @Test void zeroDocumentationChargeDoesNotClaimThatARefundWasPaid()throws Exception{
        var visa=processing("Simulation",new BigDecimal("0.00"));visas.rejectVisa(visa.getId(),"TEST rejection",null);
        assertTrue(events(customer,"PAYMENT_REFUND_RECORDED",booking.getId()).isEmpty());
        assertTrue(events(customer,"PAYMENT_REFUND_PENDING",booking.getId()).isEmpty());
    }
}
