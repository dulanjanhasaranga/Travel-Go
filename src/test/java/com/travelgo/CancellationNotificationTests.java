package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.BookingStatus;
import com.travelgo.repository.*;
import com.travelgo.security.RoleNames;
import com.travelgo.service.CancellationRequestService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Committed synthetic fixtures on a dedicated in-memory database; no application accounts are used. */
@SpringBootTest(properties={"spring.config.import=",
    "spring.datasource.url=jdbc:h2:mem:travelgo-cancellation-notifications;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false"})
@ActiveProfiles("test")
@Import({CancellationNotificationTests.Time.class,TestDatabaseSafetyConfiguration.class})
class CancellationNotificationTests {
    @TestConfiguration static class Time {
        @Bean @Primary Clock cancellationClock(){return Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"),ZoneOffset.UTC);}
    }
    @Autowired CancellationRequestService service;
    @Autowired CancellationRequestRepository requests;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired BookingRepository bookings;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx;
    User customer,other,consultant,legacyConsultant,inactiveConsultant,administrator,officer;
    Booking booking;
    static final String REASON="Synthetic private cancellation reason";

    @BeforeEach void fixture(){
        SecurityContextHolder.clearContext();
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(s->{
            // Prevent earlier synthetic consultants in this test database from receiving this fixture's alerts.
            users.findAll().forEach(u->u.setActive(false));
            customer=account("CUSTOMER");other=account("CUSTOMER");consultant=account("TRAVEL_CONSULTANT");
            legacyConsultant=account("PACKAGE_MANAGER");inactiveConsultant=account("TRAVEL_CONSULTANT");
            inactiveConsultant.setActive(false);users.save(inactiveConsultant);
            administrator=account("ADMIN");officer=account("VISA_OFFICER");
            Destination destination=new Destination();destination.setCountry("Synthetic country");destination.setCity("Synthetic city");destinations.save(destination);
            PackageCategory category=new PackageCategory();category.setName("Synthetic "+UUID.randomUUID());categories.save(category);
            TourPackage tour=new TourPackage();tour.setName("Synthetic cancellation tour");tour.setDestination(destination);tour.setCategory(category);
            tour.setBasePrice(new BigDecimal("100.00"));tour.setDurationDays(4);tour.setMaxCapacity(10);packages.save(tour);
            booking=new Booking();booking.setUser(customer);booking.setTourPackage(tour);booking.setTravelDate(LocalDate.of(2030,2,1));
            booking.setNumberOfTravelers(1);booking.setPackageUnitPrice(new BigDecimal("100.00"));booking.setTotalPackageAmount(new BigDecimal("100.00"));
            booking.setBookingStatus(BookingStatus.PENDING);booking.setPackagePaymentDeadline(LocalDateTime.of(2030,1,29,0,0));bookings.saveAndFlush(booking);
        });
        login(customer);
    }
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
    private User account(String name){
        Role role=roles.findByRoleName(name).orElseGet(()->roles.save(new Role(name,"Synthetic cancellation notification role")));
        return users.save(new User("Synthetic user",UUID.randomUUID()+"@example.invalid","unused-test-password",role));
    }
    private void login(User user){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(),"unused",
            List.of(new SimpleGrantedAuthority("ROLE_"+RoleNames.canonical(user.getRole().getRoleName())))));
    }
    private CancellationRequest request(){login(customer);return service.request(booking.getId(),REASON);}
    private List<Notification> notices(String type){
        return tx.execute(s->notifications.findAll().stream().filter(n->type.equals(n.getType())&&booking.getId().equals(n.getRelatedEntityId())).toList());
    }
    private Set<Long> recipients(String type){
        return tx.execute(s->notices(type).stream().map(n->n.getUser().getId()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void requestAlertsOnlyActiveConsultantsAndIdenticalRetryAddsNothing(){
        CancellationRequest first=request();
        assertEquals(Set.of(consultant.getId(),legacyConsultant.getId()),recipients("CANCELLATION_REQUESTED"));
        assertEquals(2,notices("CANCELLATION_REQUESTED").size());
        for(Notification notice:notices("CANCELLATION_REQUESTED")){
            assertEquals("BOOKING",notice.getRelatedEntityType());assertTrue(notice.getMessage().contains("ready for review"));
            assertFalse(notice.getMessage().contains(REASON));assertFalse(notice.isRead());
        }
        assertEquals(first.getId(),service.request(booking.getId(),REASON).getId());
        assertEquals(2,notices("CANCELLATION_REQUESTED").size());
        assertEquals(BookingStatus.PENDING,bookings.findById(booking.getId()).orElseThrow().getBookingStatus());
        assertEquals(1,requests.findByBooking_IdOrderByIdDesc(booking.getId()).size());
    }

    @Test void consultantDeclineAlertsOwnerAndIdempotentReplayDoesNotAddAnotherNotice(){
        CancellationRequest request=request();login(consultant);
        CancellationRequest result=service.close(request.getId(),request.getVersion(),"Synthetic agency response explaining the decline",false);
        assertEquals("DECLINED",result.getStatus());assertEquals(Set.of(customer.getId()),recipients("CANCELLATION_DECLINED"));
        assertEquals(1,notices("CANCELLATION_DECLINED").size());
        assertTrue(notices("CANCELLATION_DECLINED").get(0).getMessage().contains("Synthetic agency response"));
        service.close(request.getId(),request.getVersion(),"Synthetic agency response explaining the decline",false);
        assertEquals(1,notices("CANCELLATION_DECLINED").size());
        assertEquals(BookingStatus.PENDING,bookings.findById(booking.getId()).orElseThrow().getBookingStatus());
    }

    @Test void customerWithdrawalAlertsOnlyActiveConsultantsAndRetryAddsNothing(){
        CancellationRequest request=request();
        assertEquals("WITHDRAWN",service.close(request.getId(),request.getVersion(),"Synthetic changed plans",true).getStatus());
        assertEquals(Set.of(consultant.getId(),legacyConsultant.getId()),recipients("CANCELLATION_WITHDRAWN"));
        assertEquals(2,notices("CANCELLATION_WITHDRAWN").size());
        service.close(request.getId(),request.getVersion(),"Synthetic changed plans",true);
        assertEquals(2,notices("CANCELLATION_WITHDRAWN").size());assertTrue(notices("CANCELLATION_DECLINED").isEmpty());
    }

    @Test void unauthorizedMutationsCannotPublishCancellationAlerts(){
        login(other);assertThrows(AccessDeniedException.class,()->service.request(booking.getId(),REASON));
        assertTrue(notices("CANCELLATION_REQUESTED").isEmpty());
        CancellationRequest request=request();
        login(other);assertThrows(AccessDeniedException.class,()->service.close(request.getId(),request.getVersion(),"Foreign request",true));
        for(User unauthorized:List.of(customer,administrator,officer)){
            login(unauthorized);assertThrows(AccessDeniedException.class,()->service.close(request.getId(),request.getVersion(),"Unauthorized decline",false));
        }
        assertTrue(notices("CANCELLATION_DECLINED").isEmpty());assertTrue(notices("CANCELLATION_WITHDRAWN").isEmpty());
        assertEquals("REQUESTED",requests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test void staleAndInvalidCloseSubmissionsAddNoNotifications(){
        CancellationRequest request=request();login(consultant);
        assertThrows(IllegalStateException.class,()->service.close(request.getId(),request.getVersion()+1,"Synthetic stale response",false));
        assertThrows(IllegalArgumentException.class,()->service.close(request.getId(),request.getVersion()," ",false));
        assertTrue(notices("CANCELLATION_DECLINED").isEmpty());
        assertEquals("REQUESTED",requests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test void failedOuterTransactionRollsBackRequestAndItsConsultantAlerts(){
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{service.request(booking.getId(),REASON);throw new IllegalStateException("Synthetic later transaction failure");}));
        assertTrue(requests.findByBooking_IdOrderByIdDesc(booking.getId()).isEmpty());assertTrue(notices("CANCELLATION_REQUESTED").isEmpty());
    }

    @Test void failedOuterTransactionRollsBackDeclineAndItsCustomerAlert(){
        CancellationRequest request=request();login(consultant);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{service.close(request.getId(),request.getVersion(),"Synthetic decline",false);throw new IllegalStateException("Synthetic later transaction failure");}));
        assertEquals("REQUESTED",requests.findById(request.getId()).orElseThrow().getStatus());assertTrue(notices("CANCELLATION_DECLINED").isEmpty());
    }

    @Test void concurrentIdenticalRequestsCreateOneRequestAndOneNoticePerConsultant() throws Exception{
        List<CancellationRequest> results=concurrent(customer,()->service.request(booking.getId(),REASON));
        assertEquals(results.get(0).getId(),results.get(1).getId());
        assertEquals(1,requests.findByBooking_IdOrderByIdDesc(booking.getId()).size());assertEquals(2,notices("CANCELLATION_REQUESTED").size());
    }

    @Test void concurrentIdenticalDeclinesCreateOneCustomerNotice() throws Exception{
        CancellationRequest request=request();
        List<CancellationRequest> results=concurrent(consultant,()->service.close(request.getId(),request.getVersion(),"Synthetic decline",false));
        assertTrue(results.stream().allMatch(r->"DECLINED".equals(r.getStatus())));assertEquals(1,notices("CANCELLATION_DECLINED").size());
    }

    private List<CancellationRequest> concurrent(User actor,Callable<CancellationRequest> operation) throws Exception{
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        Callable<CancellationRequest> task=()->{login(actor);ready.countDown();try{assertTrue(go.await(10,TimeUnit.SECONDS));return operation.call();}finally{SecurityContextHolder.clearContext();}};
        try{
            Future<CancellationRequest> first=executor.submit(task),second=executor.submit(task);assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }finally{go.countDown();executor.shutdownNow();}
    }
}
