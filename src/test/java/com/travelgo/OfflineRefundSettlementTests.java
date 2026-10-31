package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic H2/native fixtures only; recording evidence never contacts a bank, provider or SMTP server. */
@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:travelgo-offline-refunds;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.open-in-view=true", "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false",
        "travelgo.mail.account-retry-enabled=false", "travelgo.security.staff-mfa.enabled=false"})
@ActiveProfiles("test")
@Import({TestDatabaseSafetyConfiguration.class, OfflineRefundSettlementTests.Time.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
public class OfflineRefundSettlementTests {
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2030-01-01T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);} public Instant instant(){return now;}
    }
    @TestConfiguration static class Time { @Bean @Primary MutableClock offlineClock(){return new MutableClock();} }
    @Autowired MutableClock clock;
    @Autowired OfflineRefundSettlementService service;
    @Autowired PaymentService paymentService;
    @Autowired OfflineRefundSettlementRepository settlements;
    @Autowired RefundRepository refunds;
    @Autowired PaymentRepository payments;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired AuditLogRepository audits;
    @Autowired NotificationRepository notifications;
    @Autowired ProviderCheckoutAttemptRepository checkouts;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired WebApplicationContext context;
    User officer, customer;
    MockMvc mvc;
    private static final String EVIDENCE="Synthetic completed payout receipt retained in the isolated test evidence register.";
    @BeforeEach void fixture(){
        clock.now=Instant.parse("2030-01-01T12:00:00Z");
        officer=tx().execute(s->{
            roles.findByRoleName("ADMIN").orElseGet(()->roles.save(new Role("ADMIN","Synthetic initialization reference")));
            Role role=roles.findByRoleName("VISA_OFFICER").orElseGet(()->roles.save(new Role("VISA_OFFICER","Synthetic officer")));
            var grants=new HashSet<Permission>();
            for(String name:List.of("VISA_VIEW","VISA_MANAGE","PAYMENT_VIEW","PAYMENT_MANAGE"))
                grants.add(permissions.findByPermissionName(name).orElseGet(()->permissions.save(new Permission(name,"Synthetic offline refund permission","PAYMENT"))));
            role.setPermissions(grants);roles.save(role);
            return users.save(new User("Synthetic refund officer",UUID.randomUUID()+"@example.invalid","unused-synthetic-hash",role));
        });
        customer=users.save(new User("Synthetic refund customer",UUID.randomUUID()+"@example.invalid","unused-synthetic-hash",
                roles.findByRoleName("CUSTOMER").orElseGet(()->roles.save(new Role("CUSTOMER","Synthetic customer")))));
        login(officer);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    Authentication auth(User actor){var grants=new ArrayList<SimpleGrantedAuthority>();grants.add(new SimpleGrantedAuthority("ROLE_"+actor.getRole().getRoleName()));actor.getRole().getPermissions().forEach(p->grants.add(new SimpleGrantedAuthority("PERM_"+p.getPermissionName())));return UsernamePasswordAuthenticationToken.authenticated(actor.getEmail(),null,grants);}
    void login(User actor){SecurityContextHolder.getContext().setAuthentication(auth(actor));}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.now,ZoneOffset.UTC);}
    Refund pending(String method,String amount){return tx().execute(s->{
        Destination d=new Destination();d.setCity("Synthetic refund city");d.setCountry("Synthetic refund country");destinations.save(d);
        PackageCategory category=new PackageCategory();category.setName(UUID.randomUUID().toString());categories.save(category);
        TourPackage tour=new TourPackage();tour.setName("Synthetic refund tour");tour.setDestination(d);tour.setCategory(category);tour.setBasePrice(new BigDecimal("100"));tour.setDurationDays(4);tour.setMaxCapacity(10);packages.save(tour);
        Booking b=new Booking();b.setUser(customer);b.setTourPackage(tour);b.setTravelDate(now().toLocalDate().plusMonths(1));b.setNumberOfTravelers(1);b.setPackageUnitPrice(new BigDecimal("100"));b.setTotalPackageAmount(new BigDecimal("100"));b.setBookingStatus(BookingStatus.CANCELLED);bookings.save(b);
        Payment p=new Payment();p.setBooking(b);p.setPaymentType(PaymentType.VISA_DOCUMENTATION);p.setAmount(new BigDecimal("40.00"));p.setPaymentStatus(PaymentStatus.PAID);p.setPaymentMethod(method);p.setTransactionReference("SYNTHETIC-ORIGINAL-"+UUID.randomUUID());p.setPaidAt(now().minusDays(2));payments.save(p);
        Refund r=new Refund();r.setPayment(p);r.setAmount(new BigDecimal(amount));r.setReason("Synthetic approved pending refund");r.setStatus(RefundStatus.REFUND_PENDING);return refunds.saveAndFlush(r);
    });}
    OfflineRefundSettlement record(Refund r,String channel,String reference){return service.record(r.getId(),channel,reference,r.getAmount(),"USD",now().minusHours(1),EVIDENCE,true);}
    long auditCount(Long id){return audits.findAll().stream().filter(a->a.getAction().equals("OFFLINE_REFUND_SETTLEMENT_RECORDED")&&id.equals(a.getEntityId())).count();}
    @Test void bankEvidencePreservesOriginalAmountsReferencesAndPartialPaymentState(){
        Refund r=pending("Bank Transfer","10.00");String original=r.getPayment().getTransactionReference();long before=notifications.count();BigDecimal revenueBefore=paymentService.getTotalRevenue();
        var result=record(r,"BANK_TRANSFER","bank-ref-100");
        assertEquals("BANK-REF-100",result.getReference());assertEquals("USD",result.getCurrency());assertEquals(officer.getId(),result.getRecordedBy().getId());assertEquals(EVIDENCE,result.getEvidenceSource());
        assertEquals(0,new BigDecimal("10.00").compareTo(refunds.findById(r.getId()).orElseThrow().getAmount()));
        Payment paid=payments.findById(r.getPayment().getId()).orElseThrow();assertEquals(PaymentStatus.PAID,paid.getPaymentStatus());assertEquals(original,paid.getTransactionReference());assertEquals("Bank Transfer",paid.getPaymentMethod());assertEquals(0,new BigDecimal("40.00").compareTo(paid.getAmount()));
        assertEquals(RefundStatus.REFUND_PROCESSED,refunds.findById(r.getId()).orElseThrow().getStatus());assertEquals(before+1,notifications.count());assertEquals(1,auditCount(r.getId()));
        assertEquals(0,revenueBefore.subtract(new BigDecimal("10.00")).compareTo(paymentService.getTotalRevenue()));
    }
    @Test void cashFullRefundMarksOnlyTheOriginalPaymentRefunded(){
        Refund r=pending("CASH","40.00");BigDecimal revenueBefore=paymentService.getTotalRevenue();var result=record(r,"CASH","CASH-RECEIPT-1");
        assertEquals(PaymentStatus.REFUNDED,payments.findById(r.getPayment().getId()).orElseThrow().getPaymentStatus());assertEquals(0,new BigDecimal("40.00").compareTo(result.getOriginalPaymentAmount()));assertEquals(now().minusHours(1),result.getReimbursedAt());
        assertEquals(0,revenueBefore.subtract(new BigDecimal("40.00")).compareTo(paymentService.getTotalRevenue()));
    }
    @Test void exactRetryIsIdempotentButConflictingEvidenceIsNeverOverwritten(){
        Refund r=pending("Cash Deposit","10.00");var first=record(r,"CASH","CASH-RETRY-1");long notices=notifications.count();
        assertEquals(first.getId(),record(r,"CASH","cash-retry-1").getId());assertEquals(notices,notifications.count());assertEquals(1,auditCount(r.getId()));
        assertThrows(IllegalStateException.class,()->record(r,"CASH","DIFFERENT-REFERENCE"));
        assertEquals("CASH-RETRY-1",settlements.findByRefund_Id(r.getId()).orElseThrow().getReference());
    }
    @Test void referenceCannotBeReusedForAnotherRefund(){Refund first=pending("Bank Transfer","10"),second=pending("Bank Transfer","10");record(first,"BANK_TRANSFER","SHARED-BANK-REF");assertThrows(IllegalStateException.class,()->record(second,"BANK_TRANSFER","SHARED-BANK-REF"));assertEquals(RefundStatus.REFUND_PENDING,refunds.findById(second.getId()).orElseThrow().getStatus());}
    @Test void invalidAmountCurrencyTimeOrAttestationCannotChangeTheLedger(){
        Refund r=pending("Bank Transfer","10");long count=settlements.count(),logs=audits.count(),notices=notifications.count();
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"BANK_TRANSFER","BAD-AMOUNT",new BigDecimal("9.99"),"USD",now(),EVIDENCE,true));
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"BANK_TRANSFER","BAD-CURRENCY",r.getAmount(),"LKR",now(),EVIDENCE,true));
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"BANK_TRANSFER","FUTURE-TIME",r.getAmount(),"USD",now().plusSeconds(1),EVIDENCE,true));
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"BANK_TRANSFER","EARLY-TIME",r.getAmount(),"USD",now().minusDays(3),EVIDENCE,true));
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"BANK_TRANSFER","NOT-VERIFIED",r.getAmount(),"USD",now(),EVIDENCE,false));
        assertEquals(count,settlements.count());assertEquals(logs,audits.count());assertEquals(notices,notifications.count());assertEquals(RefundStatus.REFUND_PENDING,refunds.findById(r.getId()).orElseThrow().getStatus());
    }
    @Test void evidenceAndReferenceValidationRejectsMissingOversizedOrControlInput(){
        Refund r=pending("CASH","10");
        assertThrows(IllegalArgumentException.class,()->record(r,"CASH","x"));assertThrows(IllegalArgumentException.class,()->record(r,"CASH","x".repeat(121)));
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"CASH","VALID-REF",r.getAmount(),"USD",now(),"too short",true));
        assertThrows(IllegalArgumentException.class,()->service.record(r.getId(),"CASH","VALID-REF",r.getAmount(),"USD",now(),EVIDENCE+'\u0000',true));
        assertTrue(settlements.findByRefund_Id(r.getId()).isEmpty());
    }
    @Test void simulatedProviderUnknownAndWrongChannelPaymentsAreRejected(){
        for(String method:List.of("Simulation","Stripe Checkout","PayHere Checkout","Credit / Debit Card","Cheque","Direct Deposit")){
            Refund r=pending(method,"10");assertFalse(service.canRecord(r));assertThrows(IllegalArgumentException.class,()->record(r,"BANK_TRANSFER","UNSUPPORTED-"+r.getId()));
        }
        Refund r=pending("Bank Transfer","10");assertThrows(IllegalArgumentException.class,()->record(r,"CASH","WRONG-CHANNEL"));
    }
    @Test void providerReferencesAndCheckoutLinksCannotBeDisguisedAsOffline(){
        Refund r=pending("Bank Transfer","10");tx().executeWithoutResult(s->payments.findById(r.getPayment().getId()).orElseThrow().setTransactionReference("PH-123456"));
        assertThrows(IllegalStateException.class,()->record(r,"BANK_TRANSFER","DISGUISED-REF"));
        Refund linked=pending("CASH","10");tx().executeWithoutResult(s->{var a=new ProviderCheckoutAttempt();a.setBooking(bookings.findById(linked.getPayment().getBooking().getId()).orElseThrow());a.setPaymentType(PaymentType.VISA_DOCUMENTATION);a.setAmount(new BigDecimal("40"));a.setQuoteDeadline(now().plusDays(1));a.setExpiresAt(clock.now.plusSeconds(3600).getEpochSecond());a.setPaymentMode("test");a.setDeploymentReference("synthetic-offline-rejection");a.setSettledPayment(payments.findById(linked.getPayment().getId()).orElseThrow());a.setCreatedAt(clock.now);checkouts.save(a);});
        assertThrows(IllegalStateException.class,()->record(linked,"CASH","LINKED-REF"));
    }
    @Test void nonCancelledUnpaidOversizedAndAlreadyProcessedRefundsStayUnchanged(){
        Refund active=pending("CASH","10");tx().executeWithoutResult(s->bookings.findById(active.getPayment().getBooking().getId()).orElseThrow().setBookingStatus(BookingStatus.CONFIRMED));assertThrows(IllegalStateException.class,()->record(active,"CASH","ACTIVE-REF"));
        Refund unpaid=pending("CASH","10");tx().executeWithoutResult(s->payments.findById(unpaid.getPayment().getId()).orElseThrow().setPaymentStatus(PaymentStatus.PENDING));assertThrows(IllegalStateException.class,()->record(unpaid,"CASH","UNPAID-REF"));
        Refund excess=pending("CASH","41");assertThrows(IllegalArgumentException.class,()->record(excess,"CASH","EXCESS-REF"));
        Refund done=pending("CASH","10");tx().executeWithoutResult(s->refunds.findById(done.getId()).orElseThrow().setStatus(RefundStatus.REFUND_PROCESSED));assertThrows(IllegalStateException.class,()->record(done,"CASH","DONE-REF"));
    }
    @Test void customersAdministratorsAndViewOnlyOfficersCannotRecord(){
        Refund r=pending("CASH","10");login(customer);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->record(r,"CASH","DENIED-REF"));
        User admin=users.save(new User("Synthetic admin",UUID.randomUUID()+"@example.invalid","unused",roles.findByRoleName("ADMIN").orElseThrow()));login(admin);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->record(r,"CASH","DENIED-REF"));
        tx().executeWithoutResult(s->{var role=roles.findByRoleName("VISA_OFFICER").orElseThrow();role.setPermissions(new HashSet<>(Set.of(permissions.findByPermissionName("VISA_VIEW").orElseThrow())));roles.save(role);});
        login(officer);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->record(r,"CASH","DENIED-REF"));assertTrue(settlements.findByRefund_Id(r.getId()).isEmpty());
    }
    @Test void csrfAndCurrentPermissionsProtectTheHttpCompletionForm() throws Exception {
        Refund r=pending("Bank Transfer","10");
        mvc.perform(post("/staff/payments/refund-settlements/"+r.getId()+"/offline").with(authentication(auth(officer))).param("channel","BANK_TRANSFER").param("reference","HTTP-REF-1").param("verifiedAmount","10").param("currency","USD").param("reimbursedAt",now().minusHours(1).toString()).param("evidence",EVIDENCE).param("verified","true")).andExpect(status().isForbidden());
        mvc.perform(post("/staff/payments/refund-settlements/"+r.getId()+"/offline").with(authentication(auth(officer))).with(csrf()).param("channel","BANK_TRANSFER").param("reference","HTTP-REF-1").param("verifiedAmount","10").param("currency","USD").param("reimbursedAt",now().minusHours(1).toString()).param("evidence",EVIDENCE).param("verified","true")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/staff/payments/refund-settlements"));
        assertTrue(settlements.findByRefund_Id(r.getId()).isPresent());
    }
    @Test void refundPageShowsOfflineActionAndEscapedRecordedEvidence() throws Exception {
        Refund r=pending("Bank Transfer","10");String page=mvc.perform(get("/staff/payments/refund-settlements").with(authentication(auth(officer)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("Record completed offline reimbursement"));assertTrue(page.contains("/offline"));
        login(officer);
        service.record(r.getId(),"BANK_TRANSFER","DISPLAY-REF",r.getAmount(),"USD",now(),"Synthetic payout evidence <script>window.bad=true</script> retained locally.",true);
        page=mvc.perform(get("/staff/payments/refund-settlements").with(authentication(auth(officer)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertTrue(page.contains("Recently recorded offline refunds"));assertTrue(page.contains("&lt;script&gt;"));assertFalse(page.contains("<script>window.bad"));
    }
    @Test void concurrentExactSubmissionsProduceOneEvidenceAuditAndNotice() throws Exception {
        Refund r=pending("CASH","10");long notices=notifications.count();ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try{Callable<Long> action=()->{login(officer);ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));try{return record(r,"CASH","CONCURRENT-EXACT").getId();}finally{SecurityContextHolder.clearContext();}};
            Future<Long> first=executor.submit(action),second=executor.submit(action);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));assertEquals(1,auditCount(r.getId()));assertEquals(notices+1,notifications.count());
        }finally{executor.shutdownNow();}
    }
    @Test void concurrentReferenceReuseAcrossDifferentBookingsHasOneWinner() throws Exception {
        Refund first=pending("Bank Transfer","10"),second=pending("Bank Transfer","10");ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try{List<Future<Boolean>> attempts=new ArrayList<>();for(Refund r:List.of(first,second))attempts.add(executor.submit(()->{login(officer);ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));try{record(r,"BANK_TRANSFER","CONCURRENT-SAME-REF");return true;}catch(IllegalStateException|org.springframework.dao.DataIntegrityViolationException denied){return false;}finally{SecurityContextHolder.clearContext();}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertNotEquals(attempts.get(0).get(20,TimeUnit.SECONDS),attempts.get(1).get(20,TimeUnit.SECONDS));assertEquals(1,auditCount(first.getId())+auditCount(second.getId()));
        }finally{executor.shutdownNow();}
    }
    @Test void outerRollbackCannotLeaveCompletedEvidenceLedgerAuditOrNotice(){
        Refund r=pending("CASH","10");long notices=notifications.count(),logs=audits.count();tx().executeWithoutResult(s->{record(r,"CASH","ROLLBACK-REF");s.setRollbackOnly();});assertTrue(settlements.findByRefund_Id(r.getId()).isEmpty());assertEquals(RefundStatus.REFUND_PENDING,refunds.findById(r.getId()).orElseThrow().getStatus());assertEquals(notices,notifications.count());assertEquals(logs,audits.count());
    }
    @Test void repeatableReadWithPrimedRefundSeesTheCommittedSettlementInsteadOfDuplicatingIt() throws Exception {
        Refund r=pending("CASH","10");ExecutorService executor=Executors.newSingleThreadExecutor();
        try{var repeatable=tx();repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            Runnable retryInPrimedSnapshot=()->repeatable.executeWithoutResult(s->{
                assertEquals(RefundStatus.REFUND_PENDING,refunds.findById(r.getId()).orElseThrow().getStatus());assertTrue(settlements.findByRefund_Id(r.getId()).isEmpty());
                Future<Long> first=executor.submit(()->{login(officer);try{return record(r,"CASH","PRIMED-REF").getId();}finally{SecurityContextHolder.clearContext();}});
                try{assertEquals(first.get(20,TimeUnit.SECONDS),record(r,"CASH","PRIMED-REF").getId());}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}catch(ExecutionException|TimeoutException failed){throw new IllegalStateException(failed);}
            });
            boolean h2;
            try(var connection=dataSource.getConnection()){h2="H2".equals(connection.getMetaData().getDatabaseProductName());}
            // H2 rejects a write lock on a stale REPEATABLE_READ snapshot with a serialization conflict.
            // MySQL's current locking read must see the committed evidence and return the exact retry.
            if(h2) assertThrows(org.springframework.dao.CannotAcquireLockException.class,retryInPrimedSnapshot::run);
            else retryInPrimedSnapshot.run();
            assertNotNull(record(r,"CASH","PRIMED-REF").getId());
            assertEquals(1,auditCount(r.getId()));
        }finally{executor.shutdownNow();}
    }
    @Test void viewOnlyOfficerCanReadHistoryWithoutReceivingASettlementForm() throws Exception {
        Refund paid=pending("CASH","10");record(paid,"CASH","READONLY-HISTORY");pending("Bank Transfer","10");
        tx().executeWithoutResult(s->{var role=roles.findByRoleName("VISA_OFFICER").orElseThrow();role.setPermissions(new HashSet<>(Set.of(permissions.findByPermissionName("PAYMENT_VIEW").orElseThrow())));roles.save(role);});
        User viewer=users.findById(officer.getId()).orElseThrow();
        String page=mvc.perform(get("/staff/payments/refund-settlements").with(authentication(auth(viewer)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("READONLY-HISTORY"));assertFalse(page.contains("Record verified reimbursement"));
    }
}
