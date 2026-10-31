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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Synthetic workflow fixtures only; the inherited guard forbids the configured application database. */
class AgencyOperationsWorkflowTests extends WorkflowIntegrationTests {
    @Autowired TravelPolicyService policies;
    @Autowired TravelPolicyRepository policyRepo;
    @Autowired CancellationRequestService cancellations;
    @Autowired CancellationRequestRepository cancellationRepo;
    @Autowired SupplierReservationService suppliers;
    @Autowired SupplierHoldRepository supplierRepo;
    @Autowired PermissionRepository permissionRepo;
    @Autowired WorkflowRules rules;
    @Autowired AuditService audit;
    @Autowired ProviderCheckoutAttemptRepository checkoutRepo;
    @Autowired VisaChecklistService checklists;

    @BeforeEach void prepareAgencyPermissionsAndIsolatePolicy() {
        clearPublishedPolicies();
        tx.executeWithoutResult(s->{
            var role=roleRepo.findByRoleName("ADMIN").orElseThrow();var assigned=new HashSet<>(role.getPermissions());
            for(String name:List.of("SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_MANAGE","PAYMENT_VIEW","PAYMENT_MANAGE"))
                assigned.add(permissionRepo.findByPermissionName(name).orElseGet(()->permissionRepo.save(new Permission(name,"Isolated agency operations permission","SYSTEM"))));
            role.setPermissions(assigned);roleRepo.save(role);
        });
    }
    @AfterEach void removeActivePolicyFromIsolatedFixture() { clearPublishedPolicies(); }
    private void clearPublishedPolicies() { tx.executeWithoutResult(s->policyRepo.findAll().forEach(p->p.setPublished(false))); }
    private String content(String purpose) { return ("Synthetic test agency "+purpose+". This is isolated test text, not published real agency policy. ").repeat(3); }
    private TravelPolicy draft(String title) { login(admin);return policies.draft(title,content("terms"),content("privacy"),content("cancellation")); }
    private TravelPolicy policy() { var p=draft("Synthetic agency policy");return policies.publish(p.getId(),policies.current().map(TravelPolicy::getId).orElse(null),true); }
    private BookingRequest bookingInput() { return new BookingRequest(tour.getId(),LocalDate.of(2030,2,1),1,null,List.of(traveler())); }
    private Payment upfront(Booking b) throws Exception { processing(b);return paymentRepo.findByBooking_Id(b.getId()).stream().filter(p->p.getPaymentType()==PaymentType.VISA_DOCUMENTATION&&p.getPaymentStatus()==PaymentStatus.PAID).findFirst().orElseThrow(); }
    private CancellationRequest requested(Booking b) { login(customer);return cancellations.request(b.getId(),"Synthetic cancellation request for personal reasons."); }
    private CancellationRequest quoteCancellation(CancellationRequest r,TravelPolicy p,Map<Long,BigDecimal> allocations) {
        login(consultant);return cancellations.quote(r.getId(),r.getVersion(),p.getId(),allocations,"Synthetic agency quote: stated allocations are refundable; other charges retained.",LocalDateTime.of(2030,1,2,12,0));
    }
    private CancellationRequest accepted(Booking b,Payment paid,String amount) {
        var r=requested(b);var p=policy();var q=quoteCancellation(r,p,Map.of(paid.getId(),new BigDecimal(amount)));
        login(customer);return cancellations.accept(q.getId(),q.getVersion(),true);
    }
    private Refund refund(Payment p) { return refundRepo.findByPayment_Id(p.getId()).orElseThrow(); }
    private Payment rawAttempt(Booking b,PaymentStatus status,String amount,String method) {
        var p=new Payment();p.setBooking(b);p.setPaymentType(PaymentType.FULL_PACKAGE);p.setAmount(new BigDecimal(amount));p.setPaymentMethod(method);p.setPaymentStatus(status);
        if(status==PaymentStatus.PAID)p.setPaidAt(rules.now());return paymentRepo.saveAndFlush(p);
    }
    private ProviderCheckoutAttempt hostedAttempt(Booking b,PaymentType type,String session,long expiresAt) {
        var attempt=new ProviderCheckoutAttempt();attempt.setBooking(b);attempt.setPaymentType(type);
        attempt.setAmount(type==PaymentType.VISA_DOCUMENTATION?new BigDecimal("40"):new BigDecimal("100"));
        attempt.setQuoteDeadline(b.getPackagePaymentDeadline());attempt.setExpiresAt(expiresAt);attempt.setPaymentMode("test");
        attempt.setDeploymentReference("synthetic-isolated-test");attempt.setSessionReference(session);attempt.setCreatedAt(clock.instant());
        return checkoutRepo.saveAndFlush(attempt);
    }

    @Test void requiredPublishedPolicyBlocksBookingAndRollsBackSeatAndTravelerAllocation() {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(policies);
        boolean previous=(boolean)org.springframework.test.util.ReflectionTestUtils.getField(target,"required");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"required",true);
        try {
            login(customer);long bookingCount=bookingRepo.count(),travelerCount=travelerRepo.count();
            long supplierCount=supplierRepo.count();int seats=departureRepo.findByTourPackage_Id(tour.getId()).get(0).getAvailableSeats();
            var failure=assertThrows(IllegalStateException.class,()->bookings.createBooking(bookingInput()));
            assertTrue(failure.getMessage().contains("publish approved booking policies"));
            assertEquals(bookingCount,bookingRepo.count());assertEquals(travelerCount,travelerRepo.count());assertEquals(supplierCount,supplierRepo.count());
            assertEquals(seats,departureRepo.findByTourPackage_Id(tour.getId()).get(0).getAvailableSeats());
            var published=policy();login(customer);
            var accepted=bookings.createBooking(bookingInput(),UUID.randomUUID().toString(),true,published.getId());
            assertEquals(published.getId(),bookingRepo.findById(accepted.getId()).orElseThrow().getAcceptedPolicy().getId());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"required",previous);}
    }

    @Test void configuredSupplierGateBlocksCustomerAndManualFinalPaymentsUntilEvidenceRecorded() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(suppliers);boolean previous=suppliers.isRequired();
        org.springframework.test.util.ReflectionTestUtils.setField(target,"required",true);
        try {
            var b=create(false);var visa=processing(b);login(officer);visas.approveVisa(visa.getId());
            long paymentsBefore=paymentRepo.count(),noticesBefore=notificationRepo.count();
            login(customer);assertFalse(payments.canPay(b,PaymentType.FULL_PACKAGE));
            var customerFailure=assertThrows(IllegalStateException.class,()->pay(b,PaymentType.FULL_PACKAGE,"100"));
            assertTrue(customerFailure.getMessage().contains("Supplier confirmation is required"));
            login(officer);
            for(var status:List.of(PaymentStatus.PAID,PaymentStatus.PENDING)){
                var manualFailure=assertThrows(IllegalStateException.class,()->payments.recordManualPayment(b.getId(),PaymentType.FULL_PACKAGE,
                    new BigDecimal("100"),"Simulation","SYNTHETIC-UNCONFIRMED",status,rules.now(),"Synthetic bypass attempt"));
                assertTrue(manualFailure.getMessage().contains("Supplier confirmation is required"));
            }
            assertEquals(paymentsBefore,paymentRepo.count());assertEquals(noticesBefore,notificationRepo.count());
            login(consultant);var hold=supplierRepo.findByBooking_Id(b.getId()).orElseThrow();
            suppliers.confirm(b.getId(),hold.getVersion(),"Synthetic supplier","QA-GATE-EXTERNAL","All synthetic included services acknowledged",true);
            login(customer);assertTrue(payments.canPay(b,PaymentType.FULL_PACKAGE));pay(b,PaymentType.FULL_PACKAGE,"100");
            assertEquals(BookingStatus.CONFIRMED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"required",previous);}
    }

    @Test void configuredSupplierGateBlocksStaffConfirmationEvenWithRecordedApprovedVisaAndFullPayments() throws Exception {
        var b=create(false);var visa=processing(b);login(officer);visas.approveVisa(visa.getId());
        // Explicit synthetic historical payment fixture: staff must still check supplier evidence.
        rawAttempt(b,PaymentStatus.PAID,"100","Simulation");
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(suppliers);boolean previous=suppliers.isRequired();
        org.springframework.test.util.ReflectionTestUtils.setField(target,"required",true);
        try {
            login(consultant);assertFalse(bookings.canConfirm(b));var failure=assertThrows(IllegalStateException.class,()->bookings.confirmBooking(b.getId()));
            assertTrue(failure.getMessage().contains("Supplier confirmation is required"));
            assertEquals(BookingStatus.PENDING,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
            var hold=supplierRepo.findByBooking_Id(b.getId()).orElseThrow();
            suppliers.confirm(b.getId(),hold.getVersion(),"Synthetic supplier","QA-CONFIRM-GATE","Synthetic services acknowledged",true);
            assertTrue(bookings.canConfirm(b));bookings.confirmBooking(b.getId());long notices=notificationRepo.count();bookings.confirmBooking(b.getId());
            assertEquals(notices,notificationRepo.count());assertEquals(BookingStatus.CONFIRMED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"required",previous);}
    }

    @Test void supplierConfirmationFreezesEditsButAllowsRequiredVisaAndIdempotentRetryAfterReview() throws Exception {
        var b=create(false);login(consultant);var hold=supplierRepo.findByBooking_Id(b.getId()).orElseThrow();
        suppliers.confirm(b.getId(),hold.getVersion(),"Synthetic supplier","QA-BEFORE-VISA","Synthetic services acknowledged",true);
        login(customer);assertFalse(bookings.canCustomerEdit(bookingRepo.findById(b.getId()).orElseThrow()));
        assertThrows(IllegalStateException.class,()->bookings.modifyBooking(b.getId(),LocalDate.of(2030,3,1),null,null,bookingRepo.findById(b.getId()).orElseThrow().getUpdatedAt()));
        long visasBefore=visaRepo.count();var visa=visas.createVisaApplication(b,VisaType.VISIT);assertEquals(visasBefore+1,visaRepo.count());
        documents.upload(visa.getId(),"Synthetic passport",pdf());login(officer);visas.markDocumentsVerified(visa.getId());
        long notices=notificationRepo.count(),history=historyRepo.count();login(customer);
        assertEquals(visa.getId(),visas.createVisaApplication(b,VisaType.VISIT).getId());
        assertEquals(visasBefore+1,visaRepo.count());assertEquals(notices,notificationRepo.count());assertEquals(history,historyRepo.count());
    }

    @Test void activeHostedCheckoutBlocksQuoteUntilExactExpiryAndBlocksAcceptanceIfNewCheckoutOpened() throws Exception {
        var b=create(false);var paid=upfront(b);var r=requested(b);var p=policy();
        var attempt=hostedAttempt(b,PaymentType.FULL_PACKAGE,"cs_test_synthetic_unsettled",clock.instant().getEpochSecond()+60);
        login(consultant);var quoteFailure=assertThrows(IllegalStateException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),
            Map.of(paid.getId(),BigDecimal.TEN),"Synthetic cancellation terms",rules.now().plusHours(1)));
        assertTrue(quoteFailure.getMessage().contains("hosted checkout is still open"));
        assertEquals("REQUESTED",cancellationRepo.findById(r.getId()).orElseThrow().getStatus());assertTrue(refundRepo.findByPayment_Id(paid.getId()).isEmpty());
        tx.executeWithoutResult(s->checkoutRepo.findById(attempt.getId()).orElseThrow().setExpiresAt(clock.instant().getEpochSecond()));
        var quoted=quoteCancellation(r,p,Map.of(paid.getId(),BigDecimal.TEN));
        var newer=hostedAttempt(b,PaymentType.FULL_PACKAGE,"cs_test_synthetic_newer",clock.instant().getEpochSecond()+60);
        login(customer);var acceptFailure=assertThrows(IllegalStateException.class,()->cancellations.accept(quoted.getId(),quoted.getVersion(),true));
        assertTrue(acceptFailure.getMessage().contains("hosted checkout is still open"));
        assertEquals("QUOTED",cancellationRepo.findById(r.getId()).orElseThrow().getStatus());
        assertNotEquals(BookingStatus.CANCELLED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());assertTrue(refundRepo.findByPayment_Id(paid.getId()).isEmpty());
        tx.executeWithoutResult(s->checkoutRepo.findById(newer.getId()).orElseThrow().setExpiresAt(clock.instant().getEpochSecond()));
        cancellations.accept(quoted.getId(),quoted.getVersion(),true);assertEquals(RefundStatus.REFUND_PENDING,refund(paid).getStatus());
    }

    @Test void hostedCheckoutAlreadyRecordedAsPaidDoesNotBlockCancellationQuote() throws Exception {
        var b=create(false);var visa=quote(b);login(customer);
        var checkout=context.getBean(ProviderCheckoutAttemptService.class);
        var attempt=checkout.prepare(b,PaymentType.VISA_DOCUMENTATION,visa.getTotalCharge(),visa.getPaymentExpiresAt(),"test","synthetic-agency-test");
        var request=new Payment();request.setBooking(b);request.setPaymentType(PaymentType.VISA_DOCUMENTATION);
        request.setAmount(visa.getTotalCharge());request.setPaymentMethod("Stripe Checkout");request.setTransactionReference("pi_SyntheticAgency"+b.getId());
        var paid=payments.processProviderPayment(request,attempt.getId(),"cs_test_SyntheticAgency"+b.getId());
        var r=requested(b);var p=policy();
        var quoted=quoteCancellation(r,p,Map.of(paid.getId(),BigDecimal.TEN));assertEquals("QUOTED",quoted.getStatus());
    }

    @Test void policyDraftRequiresStoredAdminPermissionAndPublicationAgencyApproval() {
        login(customer);assertThrows(AccessDeniedException.class,()->policies.draft("Invalid role",content("terms"),content("privacy"),content("refunds")));
        login(admin);assertThrows(IllegalArgumentException.class,()->policies.draft("Bad","too short",content("privacy"),content("refunds")));
        var p=draft("Synthetic approval policy");assertTrue(policies.current().isEmpty());
        assertThrows(IllegalArgumentException.class,()->policies.publish(p.getId(),null,false));
        assertFalse(policyRepo.findById(p.getId()).orElseThrow().isPublished());
        var role=new Role("ROLE_ADMIN","Isolated administrator without required permission");roleRepo.save(role);
        var u=new User("Unpermitted test admin",UUID.randomUUID()+"@example.invalid","unused",role);userRepo.save(u);login(u);
        assertThrows(AccessDeniedException.class,()->policies.publish(p.getId(),null,true));
    }
    @Test void fixedClockPublishingOlderDraftMakesItCurrentAndRetainsPreviousContent() {
        var older=draft("Older synthetic draft");var newer=draft("Newer synthetic draft");
        var first=policies.publish(newer.getId(),null,true);var next=policies.publish(older.getId(),first.getId(),true);
        assertEquals(older.getId(),policies.current().orElseThrow().getId());assertTrue(next.getPublishedAt().isAfter(first.getPublishedAt()));
        assertEquals(content("terms").trim(),policies.published(newer.getId()).getTerms());
        assertEquals(next.getId(),policies.publish(next.getId(),null,false).getId());
    }
    @Test void concurrentPolicyPublicationRejectsStaleExpectedCurrentVersion() throws Exception {
        var first=draft("Concurrent policy one");var second=draft("Concurrent policy two");
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<TravelPolicy,Callable<Boolean>> task=p->()->{login(admin);ready.countDown();try {assertTrue(go.await(20,TimeUnit.SECONDS));policies.publish(p.getId(),null,true);return true;}catch(IllegalStateException expected){return false;}finally{SecurityContextHolder.clearContext();}};
            var a=pool.submit(task.apply(first));var b=pool.submit(task.apply(second));assertTrue(ready.await(20,TimeUnit.SECONDS));go.countDown();
            assertNotEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,policyRepo.findAll().stream().filter(TravelPolicy::isPublished).count());
        } finally {pool.shutdownNow();}
    }
    @Test void bookingRejectsMissingAndStalePolicyAcceptanceAndRecordsAcceptedSnapshot() {
        var first=policy();var later=draft("Later synthetic policy");policies.publish(later.getId(),first.getId(),true);login(customer);
        long before=bookingRepo.count();int seats=departureRepo.findByTourPackage_Id(tour.getId()).get(0).getAvailableSeats();
        assertThrows(IllegalArgumentException.class,()->bookings.createBooking(bookingInput(),UUID.randomUUID().toString(),false,later.getId()));
        assertThrows(IllegalArgumentException.class,()->bookings.createBooking(bookingInput(),UUID.randomUUID().toString(),true,first.getId()));
        assertEquals(before,bookingRepo.count());assertEquals(seats,departureRepo.findByTourPackage_Id(tour.getId()).get(0).getAvailableSeats());
        var b=bookings.createBooking(bookingInput(),UUID.randomUUID().toString(),true,later.getId());
        assertEquals(later.getId(),bookingRepo.findById(b.getId()).orElseThrow().getAcceptedPolicy().getId());assertNotNull(bookingRepo.findById(b.getId()).orElseThrow().getPolicyAcceptedAt());
    }
    @Test void supplierConfirmationRequiresActualEvidenceAndIsIdempotent() {
        var b=create(false);var h=supplierRepo.findByBooking_Id(b.getId()).orElseThrow();
        login(customer);assertThrows(AccessDeniedException.class,()->suppliers.confirm(b.getId(),h.getVersion(),"Synthetic supplier","EXT-TEST-1","All synthetic services confirmed",true));
        login(consultant);assertThrows(IllegalArgumentException.class,()->suppliers.confirm(b.getId(),h.getVersion(),"Synthetic supplier",h.getSupplierReference(),"All synthetic services confirmed",true));
        assertThrows(IllegalArgumentException.class,()->suppliers.confirm(b.getId(),h.getVersion(),"Synthetic supplier","EXT-TEST-1","All synthetic services confirmed",false));
        var mandatory=new SupplierReservationService(supplierRepo,rules,audit,true);assertThrows(IllegalStateException.class,()->mandatory.requireReady(b));
        var confirmed=suppliers.confirm(b.getId(),h.getVersion(),"Synthetic supplier","EXT-TEST-1","All synthetic services confirmed",true);long notices=notificationRepo.count();
        assertTrue(suppliers.confirmed(b));mandatory.requireReady(b);
        assertEquals(confirmed.getId(),suppliers.confirm(b.getId(),h.getVersion(),"Synthetic supplier","EXT-TEST-1","All synthetic services confirmed",true).getId());assertEquals(notices,notificationRepo.count());
        assertThrows(IllegalStateException.class,()->suppliers.confirm(b.getId(),confirmed.getVersion(),"Another supplier","EXT-CHANGED","Changed services",true));
    }
    @Test void concurrentIdenticalSupplierConfirmationReturnsOneResultAndSendsOneNotice() throws Exception {
        var booking=create(false);var original=supplierRepo.findByBooking_Id(booking.getId()).orElseThrow();
        long holdsBefore=supplierRepo.count();
        long noticesBefore=notificationRepo.findAll().stream().filter(n->"SUPPLIER_CONFIRMED".equals(n.getType())
            && booking.getId().equals(n.getRelatedEntityId())).count();
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<List<Long>> confirmation=()->{
                login(consultant);ready.countDown();
                try {
                    assertTrue(go.await(20,TimeUnit.SECONDS));
                    var confirmed=suppliers.confirm(booking.getId(),original.getVersion(),"Synthetic concurrent supplier",
                        "QA-CONCURRENT-EXTERNAL","Identical synthetic included-service evidence",true);
                    return List.of(confirmed.getId(),confirmed.getVersion());
                } finally {SecurityContextHolder.clearContext();}
            };
            var first=pool.submit(confirmation);var second=pool.submit(confirmation);
            assertTrue(ready.await(20,TimeUnit.SECONDS));go.countDown();
            var firstResult=first.get(30,TimeUnit.SECONDS);assertEquals(firstResult,second.get(30,TimeUnit.SECONDS));
            assertEquals(original.getId(),firstResult.get(0));assertEquals(original.getVersion()+1,firstResult.get(1));
            var persisted=supplierRepo.findByBooking_Id(booking.getId()).orElseThrow();
            assertTrue(persisted.hasConfirmationEvidence());assertEquals("QA-CONCURRENT-EXTERNAL",persisted.getConfirmationReference());
            assertEquals(holdsBefore,supplierRepo.count());
            assertEquals(noticesBefore+1,notificationRepo.findAll().stream().filter(n->"SUPPLIER_CONFIRMED".equals(n.getType())
                && booking.getId().equals(n.getRelatedEntityId())).count());
        } finally {go.countDown();pool.shutdownNow();}
    }
    @Test void supplierConfirmationRejectsStaleFormsAndLocksCustomerChanges() {
        var b=create(false);var h=supplierRepo.findByBooking_Id(b.getId()).orElseThrow();Long version=h.getVersion();
        tx.executeWithoutResult(s->supplierRepo.findById(h.getId()).orElseThrow().setHoldExpiresAt(rules.now().plusHours(1)));
        login(consultant);assertThrows(IllegalStateException.class,()->suppliers.confirm(b.getId(),version,"Synthetic supplier","EXT-STALE","All services",true));
        var current=supplierRepo.findById(h.getId()).orElseThrow();suppliers.confirm(b.getId(),current.getVersion(),"Synthetic supplier","EXT-CURRENT","All services",true);
        login(customer);assertFalse(bookings.canCustomerEdit(bookingRepo.findById(b.getId()).orElseThrow()));
        assertThrows(IllegalStateException.class,()->bookings.modifyBooking(b.getId(),LocalDate.of(2030,3,1),null,null,bookingRepo.findById(b.getId()).orElseThrow().getUpdatedAt()));
        assertThrows(IllegalStateException.class,()->bookings.cancelBooking(b.getId()));
    }
    @Test void cancellationRequestEnforcesOwnershipAndRepeatedRequestDoesNotCloseBooking() {
        var b=create(false);login(other);assertThrows(AccessDeniedException.class,()->cancellations.request(b.getId(),"Foreign attempt"));
        var r=requested(b);long count=cancellationRepo.count();assertEquals(r.getId(),cancellations.request(b.getId(),r.getReason()).getId());assertEquals(count,cancellationRepo.count());
        assertEquals(BookingStatus.PENDING,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
        assertThrows(IllegalStateException.class,()->cancellations.request(b.getId(),"Different second request"));
        login(other);assertThrows(AccessDeniedException.class,()->cancellations.forBooking(b));assertThrows(AccessDeniedException.class,()->cancellations.accept(r.getId(),r.getVersion(),true));
    }
    @Test void cancellationQuoteRequiresCompleteBoundedAllocationsAndCurrentVersion() throws Exception {
        var b=create(false);var paid=upfront(b);var r=requested(b);var p=policy();login(consultant);
        assertThrows(IllegalArgumentException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),Map.of(),"Synthetic terms",rules.now().plusHours(1)));
        assertThrows(IllegalArgumentException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),Map.of(paid.getId(),new BigDecimal("41")),"Synthetic terms",rules.now().plusHours(1)));
        assertThrows(IllegalArgumentException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),Map.of(paid.getId(),BigDecimal.ONE.negate()),"Synthetic terms",rules.now().plusHours(1)));
        var q=quoteCancellation(r,p,Map.of(paid.getId(),BigDecimal.TEN));
        assertThrows(IllegalStateException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),Map.of(paid.getId(),BigDecimal.ONE),"Stale terms",rules.now().plusHours(1)));
        assertEquals(0,new BigDecimal("10").compareTo(tx.execute(s->cancellationRepo.findById(q.getId()).orElseThrow().getRefundTotal())));
    }
    @Test void cancellationQuoteRejectsAmbiguousUpfrontAmountAndPendingAttempts() throws Exception {
        var b=create(false);var paid=upfront(b);var r=requested(b);var p=policy();
        tx.executeWithoutResult(s->paymentRepo.findById(paid.getId()).orElseThrow().setAmount(new BigDecimal("39")));
        login(consultant);assertThrows(IllegalStateException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),Map.of(paid.getId(),BigDecimal.TEN),"Synthetic terms",rules.now().plusHours(1)));
        tx.executeWithoutResult(s->paymentRepo.findById(paid.getId()).orElseThrow().setAmount(new BigDecimal("40")));
        rawAttempt(b,PaymentStatus.PENDING,"100","Stripe");
        assertThrows(IllegalStateException.class,()->cancellations.quote(r.getId(),r.getVersion(),p.getId(),Map.of(paid.getId(),BigDecimal.TEN),"Synthetic terms",rules.now().plusHours(1)));
        assertEquals("REQUESTED",cancellationRepo.findById(r.getId()).orElseThrow().getStatus());
    }
    @Test void cancellationAcceptanceRequiresExplicitApprovalUnexpiredAndCurrentQuote() throws Exception {
        var b=create(false);var paid=upfront(b);var r=requested(b);var p=policy();var q=quoteCancellation(r,p,Map.of(paid.getId(),BigDecimal.TEN));
        login(customer);assertThrows(IllegalArgumentException.class,()->cancellations.accept(q.getId(),q.getVersion(),false));
        assertThrows(IllegalStateException.class,()->cancellations.accept(q.getId(),r.getVersion(),true));
        clock.instant=Instant.parse("2030-01-02T12:00:00Z");assertThrows(IllegalStateException.class,()->cancellations.accept(q.getId(),q.getVersion(),true));
        assertEquals("QUOTED",cancellationRepo.findById(q.getId()).orElseThrow().getStatus());assertTrue(refundRepo.findByPayment_Id(paid.getId()).isEmpty());
    }
    @Test void paymentChangesAfterQuoteRequireRequoteWithoutCancellingBooking() throws Exception {
        var b=create(false);var paid=upfront(b);var r=requested(b);var p=policy();var q=quoteCancellation(r,p,Map.of(paid.getId(),BigDecimal.TEN));
        tx.executeWithoutResult(s->paymentRepo.findById(paid.getId()).orElseThrow().setTransactionReference("changed-synthetic-reference-"+UUID.randomUUID()));
        login(customer);assertThrows(IllegalStateException.class,()->cancellations.accept(q.getId(),q.getVersion(),true));
        assertNotEquals(BookingStatus.CANCELLED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());assertTrue(refundRepo.findByPayment_Id(paid.getId()).isEmpty());
    }
    @Test void unpaidCancellationAcceptsZeroRefundAndReleasesSeatsOnce() {
        var b=create(false);int seats=departureRepo.findById(b.getDeparture().getId()).orElseThrow().getAvailableSeats();
        var r=requested(b);var p=policy();var q=quoteCancellation(r,p,Map.of());login(customer);
        cancellations.accept(q.getId(),q.getVersion(),true);long count=refundRepo.count();cancellations.accept(q.getId(),q.getVersion(),true);
        assertEquals(BookingStatus.CANCELLED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());assertEquals(count,refundRepo.count());
        assertEquals(seats+1,departureRepo.findById(b.getDeparture().getId()).orElseThrow().getAvailableSeats());
    }
    @Test void partialCancellationRefundStaysPendingUntilSimulatedSettlementAndPreservesPaidAmount() throws Exception {
        var b=create(false);var paid=upfront(b);BigDecimal before=payments.getTotalRevenue();var accepted=accepted(b,paid,"15");var r=refund(paid);
        assertEquals(RefundStatus.REFUND_PENDING,r.getStatus());assertNull(r.getProcessedAt());assertNull(r.getSettlementReference());assertEquals(0,before.compareTo(payments.getTotalRevenue()));
        login(officer);cancellations.settleSimulatedRefund(r.getId(),"PARTIAL-TEST");long notices=notificationRepo.count();
        cancellations.settleSimulatedRefund(r.getId(),"Repeated submission");assertEquals(notices,notificationRepo.count());
        assertEquals(RefundStatus.REFUND_PROCESSED,refund(paid).getStatus());assertEquals("SIM-PARTIAL-TEST",refund(paid).getSettlementReference());
        assertEquals(PaymentStatus.PAID,paymentRepo.findById(paid.getId()).orElseThrow().getPaymentStatus());assertEquals(0,new BigDecimal("40").compareTo(paymentRepo.findById(paid.getId()).orElseThrow().getAmount()));
        assertEquals(0,before.subtract(new BigDecimal("15")).compareTo(payments.getTotalRevenue()));assertEquals("ACCEPTED",accepted.getStatus());
    }
    @Test void fullCancellationRefundMarksRefundedWithoutOverwritingOriginalAmount() throws Exception {
        var b=create(false);var paid=upfront(b);BigDecimal before=payments.getTotalRevenue();accepted(b,paid,"40");var r=refund(paid);login(officer);
        cancellations.settleSimulatedRefund(r.getId(),"FULL-TEST");
        assertEquals(PaymentStatus.REFUNDED,paymentRepo.findById(paid.getId()).orElseThrow().getPaymentStatus());assertEquals(0,new BigDecimal("40").compareTo(paymentRepo.findById(paid.getId()).orElseThrow().getAmount()));
        assertEquals(0,before.subtract(new BigDecimal("40")).compareTo(payments.getTotalRevenue()));
    }
    @Test void settlementRejectsUnpaidAndRealPaymentChannelsWithoutFalseProcessedStatus() {
        var b=create(false);var payment=rawAttempt(b,PaymentStatus.FAILED,"100","Simulation");
        tx.executeWithoutResult(s->bookingRepo.findById(b.getId()).orElseThrow().setBookingStatus(BookingStatus.CANCELLED));
        var r=new Refund();r.setPayment(payment);r.setAmount(BigDecimal.TEN);r.setStatus(RefundStatus.REFUND_PENDING);refundRepo.saveAndFlush(r);login(officer);
        assertThrows(IllegalStateException.class,()->cancellations.settleSimulatedRefund(r.getId(),"NOT-PAID"));
        tx.executeWithoutResult(s->{var p=paymentRepo.findById(payment.getId()).orElseThrow();p.setPaymentStatus(PaymentStatus.PAID);p.setPaidAt(rules.now());p.setPaymentMethod("Bank transfer");});
        assertThrows(IllegalStateException.class,()->cancellations.settleSimulatedRefund(r.getId(),"REAL-CHANNEL"));
        tx.executeWithoutResult(s->paymentRepo.findById(payment.getId()).orElseThrow().setPaymentMethod("Stripe"));
        assertThrows(IllegalStateException.class,()->cancellations.settleSimulatedRefund(r.getId(),"STRIPE"));
        assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findById(r.getId()).orElseThrow().getStatus());assertNull(refundRepo.findById(r.getId()).orElseThrow().getProcessedAt());
    }
    @Test void concurrentCancellationAcceptanceProducesOneRefundAndSeatRelease() throws Exception {
        var b=create(false);var paid=upfront(b);var r=requested(b);var p=policy();var q=quoteCancellation(r,p,Map.of(paid.getId(),BigDecimal.TEN));
        int seats=departureRepo.findById(b.getDeparture().getId()).orElseThrow().getAvailableSeats();long refundsBefore=refundRepo.count();
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Long> action=()->{login(customer);ready.countDown();try {assertTrue(go.await(20,TimeUnit.SECONDS));return cancellations.accept(q.getId(),q.getVersion(),true).getId();}finally{SecurityContextHolder.clearContext();}};
            var one=pool.submit(action);var two=pool.submit(action);assertTrue(ready.await(20,TimeUnit.SECONDS));go.countDown();assertEquals(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));
            assertEquals(refundsBefore+1,refundRepo.count());assertEquals(seats+1,departureRepo.findById(b.getDeparture().getId()).orElseThrow().getAvailableSeats());
        } finally {pool.shutdownNow();}
    }
    @Test void cancellationUsesBookingAcceptedPolicyEvenAfterNewPublication() {
        var first=policy();login(customer);var b=bookings.createBooking(bookingInput(),UUID.randomUUID().toString(),true,first.getId());var r=requested(b);
        var second=policy();login(consultant);assertThrows(IllegalArgumentException.class,()->cancellations.quote(r.getId(),r.getVersion(),second.getId(),Map.of(),"Wrong policy terms",rules.now().plusHours(1)));
        var q=quoteCancellation(r,first,Map.of());assertEquals(first.getId(),q.getPolicy().getId());
    }
    @Test void mvcRendersAgencyPoliciesSupplierCancellationAndRefundPagesWithRoleAndCsrfChecks() throws Exception {
        var b=create(false);var r=requested(b);var p=policy();
        mvc.perform(get("/terms")).andExpect(status().isOk()).andExpect(view().name("travel-policy"));
        mvc.perform(get("/policies/"+p.getId()+"/privacy")).andExpect(status().isOk());
        mvc.perform(get("/admin/travel-policies").with(staffUser(admin.getEmail(),"ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/staff/bookings/"+b.getId()+"/suppliers").with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isOk());
        mvc.perform(get("/staff/bookings/cancellations").with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isOk());
        mvc.perform(get("/staff/bookings/cancellations/"+r.getId()).with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isOk());
        mvc.perform(get("/customer/bookings/"+b.getId()+"/cancellation").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk());
        mvc.perform(get("/customer/bookings/"+b.getId()+"/cancellation").with(user(other.getEmail()).roles("CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(get("/staff/payments/refund-settlements").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk());
        mvc.perform(get("/staff/bookings/cancellations").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(post("/staff/bookings/"+b.getId()+"/suppliers").with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isForbidden());
    }

    @Test void acceptedCancellationLeavesVisaAndSupplierRecordsAvailableWithoutFurtherWorkflowActions() throws Exception {
        var b=create(false);var paid=upfront(b);var visa=visaRepo.findByBooking_Id(b.getId()).orElseThrow();
        var doc=documentRepo.findByVisaApplication_Id(visa.getId()).get(0);
        var hold=supplierRepo.findByBooking_Id(b.getId()).orElseThrow();login(consultant);
        suppliers.confirm(b.getId(),hold.getVersion(),"Synthetic archived supplier","QA-ARCHIVED-SUPPLIER","Synthetic confirmed services retained after cancellation",true);
        accepted(b,paid,"15");long historyBefore=historyRepo.count();
        for(String path:List.of("/staff/visas","/staff/dashboard")) {
            var html=staffPage(path,officer);
            assertTrue(html.contains("Booking cancelled"),path);
            assertNoVisaMutationForms(html,visa.getId(),doc.getId());
        }
        var visaHtml=staffPage("/staff/visas",officer);
        assertTrue(visaHtml.contains("/visa-documents/"+doc.getId()+"/download"));
        mvc.perform(get("/visa-documents/"+doc.getId()+"/download").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk());
        var customerHtml=staffPage("/customer/visas",customer);
        assertTrue(customerHtml.contains("Booking cancelled"));
        assertTrue(customerHtml.contains("last visa status"));
        assertTrue(customerHtml.contains("/visa-documents/"+doc.getId()+"/download"));
        assertFalse(postActions(customerHtml).contains("/customer/visas/"+visa.getId()+"/documents"));
        var supplierHtml=staffPage("/staff/bookings/"+b.getId()+"/suppliers",consultant);
        assertFalse(postActions(supplierHtml).contains("/staff/bookings/"+b.getId()+"/suppliers"));
        assertTrue(supplierHtml.contains("QA-ARCHIVED-SUPPLIER"));
        assertTrue(supplierHtml.contains("Synthetic confirmed services retained after cancellation"));
        assertEquals(VisaStatus.PROCESSING,visaRepo.findById(visa.getId()).orElseThrow().getStatus());
        assertEquals(historyBefore,historyRepo.count(),"Viewing archived records must not invent visa history.");
    }

    @Test void closedBookingStatesSuppressVisaMutationsAtEveryActionableRecordedStage() throws Exception {
        var b=create(false);var visa=apply(b);var doc=documents.upload(visa.getId(),"Synthetic archived passport",pdf());
        login(officer);
        var request=new com.travelgo.dto.VisaChecklistRequest(tour.getDestination().getId(),visa.getVisaType(),
            "https://authority.example.invalid/archived-fixture",LocalDate.of(2029,12,31),null,null,null,
            "Synthetic isolated archived-record requirements","Synthetic evidence",null);
        var draft=checklists.createDraft(request);var published=checklists.publish(draft.getId(),draft.getLockVersion());
        long historyBefore=historyRepo.count();
        for(var closed:List.of(BookingStatus.CANCELLED,BookingStatus.DECLINED,BookingStatus.EXPIRED,BookingStatus.CONFIRMED)) {
            for(var stage:List.of(VisaStatus.PENDING_DOCUMENTS,VisaStatus.DOCUMENTS_REQUIRED,VisaStatus.DOCUMENTS_SUBMITTED,
                    VisaStatus.DOCUMENTS_VERIFIED,VisaStatus.PAYMENT_PENDING,VisaStatus.EXPIRED,VisaStatus.PROCESSING)) {
                // Deliberately retain possible historical combinations. The UI must not
                // rewrite them or offer actions that the transactional service rejects.
                tx.executeWithoutResult(s->{
                    bookingRepo.findById(b.getId()).orElseThrow().setBookingStatus(closed);
                    var stored=visaRepo.findById(visa.getId()).orElseThrow();stored.setStatus(stage);
                    stored.setPaymentExpiresAt(rules.now().minusHours(1));
                    stored.setChecklist(stage==VisaStatus.DOCUMENTS_SUBMITTED?published:null);
                });
                for(String path:List.of("/staff/visas","/staff/dashboard")) {
                    var html=staffPage(path,officer);
                    assertTrue(html.contains("Booking "+closed.name().toLowerCase(Locale.ROOT)),path+" "+closed+" / "+stage);
                    assertNoVisaMutationForms(html,visa.getId(),doc.getId());
                }
                var customerHtml=staffPage("/customer/visas",customer);
                assertTrue(customerHtml.contains("Booking "+closed.name().toLowerCase(Locale.ROOT)));
                assertFalse(postActions(customerHtml).contains("/customer/visas/"+visa.getId()+"/documents"));
                assertTrue(customerHtml.contains("/visa-documents/"+doc.getId()+"/download"));
                assertEquals(stage,visaRepo.findById(visa.getId()).orElseThrow().getStatus());
            }
            if(closed!=BookingStatus.CONFIRMED) {
                var supplierHtml=staffPage("/staff/bookings/"+b.getId()+"/suppliers",consultant);
                assertFalse(postActions(supplierHtml).contains("/staff/bookings/"+b.getId()+"/suppliers"));
            }
        }
        assertEquals(historyBefore,historyRepo.count());
        // The gate must retain appropriate actions for every eligible booking state.
        for(var open:List.of(BookingStatus.PENDING,BookingStatus.PROCESSING,BookingStatus.INFO_REQUIRED)) {
            tx.executeWithoutResult(s->{bookingRepo.findById(b.getId()).orElseThrow().setBookingStatus(open);
                visaRepo.findById(visa.getId()).orElseThrow().setStatus(VisaStatus.PROCESSING);});
            for(String path:List.of("/staff/visas","/staff/dashboard"))
                assertTrue(postActions(staffPage(path,officer)).contains("/staff/visas/"+visa.getId()+"/approve"),path+" "+open);
            tx.executeWithoutResult(s->visaRepo.findById(visa.getId()).orElseThrow().setStatus(VisaStatus.DOCUMENTS_SUBMITTED));
            assertTrue(postActions(staffPage("/customer/visas",customer)).contains("/customer/visas/"+visa.getId()+"/documents"));
        }
    }

    private String staffPage(String path,User actor) throws Exception {
        SecurityContextHolder.clearContext();
        return mvc.perform(get(path).with(staffUser(actor.getEmail(),actor.getRole().getRoleName())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private Set<String> postActions(String html) {
        var actions=new HashSet<String>();
        var forms=java.util.regex.Pattern.compile("<form\\b([^>]*)>",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        var method=java.util.regex.Pattern.compile("\\bmethod=[\"']post[\"']",java.util.regex.Pattern.CASE_INSENSITIVE);
        var action=java.util.regex.Pattern.compile("\\baction=[\"']([^\"']*)[\"']",java.util.regex.Pattern.CASE_INSENSITIVE);
        while(forms.find())if(method.matcher(forms.group(1)).find()) {
            var match=action.matcher(forms.group(1));if(match.find())actions.add(match.group(1));
        }
        return actions;
    }
    private void assertNoVisaMutationForms(String html,Long visaId,Long documentId) {
        var actions=postActions(html);
        for(String operation:List.of("verify-all","charges","request-documents","reissue","approve","reject","process"))
            assertFalse(actions.contains("/staff/visas/"+visaId+"/"+operation),operation+" must be unavailable for a closed booking.");
        assertFalse(actions.contains("/staff/visas/documents/"+documentId+"/verify"));
        assertFalse(actions.contains("/staff/visas/checklists/applications/"+visaId+"/attach"));
        assertFalse(actions.contains("/staff/visas/checklists/documents/"+documentId+"/assign"));
    }
}
