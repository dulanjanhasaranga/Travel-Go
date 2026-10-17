package com.travelgo;

import com.travelgo.dto.VisaChecklistRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.VisaChecklistRepository;
import com.travelgo.service.VisaChecklistService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Inherited safety guard ensures every fixture and upload uses only the dedicated test database. */
class VisaChecklistTests extends WorkflowIntegrationTests {
    @Autowired VisaChecklistService checklists;
    @Autowired VisaChecklistRepository checklistRepo;
    VisaChecklistRequest request(String required,Integer lead) {
        return new VisaChecklistRequest(tour.getDestination().getId(),VisaType.VISIT,"https://authority.example.invalid/requirements",
                LocalDate.of(2029,12,31),lead,null,null,"Synthetic isolated-test guidance; officer assesses all travelers.",required,"Test supporting evidence");
    }
    VisaChecklist publish(String required,Integer lead) {
        login(officer); var draft=checklists.createDraft(request(required,lead)); return checklists.publish(draft.getId(),draft.getLockVersion());
    }
    Long requiredId(VisaChecklist c) { return c.getItems().stream().filter(VisaChecklistItem::isRequired).findFirst().orElseThrow().getId(); }
    @Test void draftDoesNotChangeCustomerRequirementsUntilPublished() {
        login(officer); var draft=checklists.createDraft(request("Test passport evidence",null));
        login(customer); var booking=create(false); assertNull(checklists.forCustomerBooking(booking.getId(),VisaType.VISIT).id());
        var visa=apply(booking); assertNull(visaRepo.findById(visa.getId()).orElseThrow().getChecklist());
        login(officer); checklists.publish(draft.getId(),draft.getLockVersion());
        login(customer); assertNotNull(checklists.forCustomerBooking(booking.getId(),VisaType.VISIT).id());
        assertNull(checklists.forVisa(visa.getId()).id());
    }
    @Test void newApplicationsCapturePublishedRevisionAndSupersedingDoesNotChangeIt() {
        var first=publish("Test passport evidence",null); var visa=apply(create(false));
        var second=publish("Different test evidence",null);
        assertEquals(2,second.getRevision()); assertEquals(VisaChecklist.Status.RETIRED,checklistRepo.findById(first.getId()).orElseThrow().getStatus());
        login(customer); var snapshot=checklists.forVisa(visa.getId()); assertEquals(first.getId(),snapshot.id());
        assertEquals("Test passport evidence",snapshot.items().get(0).label());
        assertEquals(second.getId(),checklists.forCustomerBooking(visa.getBooking().getId(),VisaType.VISIT).id());
    }
    @Test void publishingRequiresReviewedSourceAndRequiredEvidence() {
        login(officer); var empty=checklists.createDraft(request("",null));
        assertThrows(IllegalStateException.class,()->checklists.publish(empty.getId(),empty.getLockVersion()));
        var r=request("Test evidence",null);
        var noSource=checklists.createDraft(new VisaChecklistRequest(r.destinationId(),r.visaType(),null,null,null,null,null,null,r.requiredDocuments(),null));
        assertThrows(IllegalStateException.class,()->checklists.publish(noSource.getId(),noSource.getLockVersion()));
    }
    @Test void duplicateLabelsUnsafeSourceAndInvalidEstimatesAreRejected() {
        login(officer); assertThrows(IllegalArgumentException.class,()->checklists.createDraft(request("Test passport\nTEST PASSPORT",null)));
        var r=request("Test evidence",null);
        for (String source:List.of("http://authority.example.invalid/test","javascript:alert(1)","https://user:password@authority.example.invalid/test"))
            assertThrows(IllegalArgumentException.class,()->checklists.createDraft(new VisaChecklistRequest(r.destinationId(),r.visaType(),source,r.sourceReviewedOn(),null,null,null,null,r.requiredDocuments(),null)));
        assertThrows(IllegalArgumentException.class,()->checklists.createDraft(new VisaChecklistRequest(r.destinationId(),r.visaType(),r.sourceUrl(),r.sourceReviewedOn(),null,15,10,null,r.requiredDocuments(),null)));
        assertThrows(IllegalArgumentException.class,()->checklists.createDraft(request("Test evidence",0)));
    }
    @Test void futureSourceDateAndInactiveDestinationCannotBePublished() {
        login(officer); var r=request("Test evidence",null);
        assertThrows(IllegalArgumentException.class,()->checklists.createDraft(new VisaChecklistRequest(r.destinationId(),r.visaType(),r.sourceUrl(),LocalDate.of(2030,1,2),null,null,null,null,r.requiredDocuments(),null)));
        var draft=checklists.createDraft(r);
        tx.executeWithoutResult(s->{var d=destinationRepo.findById(r.destinationId()).orElseThrow();d.setActive(false);});
        assertThrows(IllegalStateException.class,()->checklists.publish(draft.getId(),draft.getLockVersion()));
    }
    @Test void staleVersionsCannotUpdatePublishOrRetireAndPublishedItemsAreImmutable() {
        login(officer); var draft=checklists.createDraft(request("Test evidence",null)); Long old=draft.getLockVersion();
        var updated=checklists.updateDraft(draft.getId(),old,request("Revised test evidence",null));
        assertThrows(IllegalStateException.class,()->checklists.updateDraft(draft.getId(),old,request("Stale evidence",null)));
        assertThrows(IllegalStateException.class,()->checklists.publish(draft.getId(),old));
        var published=checklists.publish(draft.getId(),updated.getLockVersion());
        assertThrows(IllegalStateException.class,()->checklists.updateDraft(published.getId(),published.getLockVersion(),request("Cannot overwrite",null)));
        assertThrows(IllegalStateException.class,()->checklists.retire(published.getId(),old));
    }
    @Test void filenameAloneCannotSatisfyRequiredUploadAndExplicitLinkEnablesOfficerAssessment() throws Exception {
        var c=publish("Test passport evidence",null); var visa=apply(create(false));
        documents.upload(visa.getId(),"Test passport evidence",pdf()); login(officer);
        assertThrows(IllegalStateException.class,()->visas.markDocumentsVerified(visa.getId()));
        assertEquals(VisaStatus.DOCUMENTS_SUBMITTED,visaRepo.findById(visa.getId()).orElseThrow().getStatus());
        login(customer); var linked=documents.upload(visa.getId(),"Clear synthetic copy",pdf(),requiredId(c));
        assertFalse(linked.isVerified()); assertTrue(checklists.forVisa(visa.getId()).items().get(0).uploaded());
        login(officer); visas.markDocumentsVerified(visa.getId());
        assertEquals(VisaStatus.DOCUMENTS_VERIFIED,visaRepo.findById(visa.getId()).orElseThrow().getStatus());
    }
    @Test void uploadsCannotLinkAnotherRevisionOrAnotherCustomersApplication() throws Exception {
        var first=publish("First test evidence",null); var visa=apply(create(false)); var second=publish("Second test evidence",null);
        login(customer); long count=documentRepo.count();
        assertThrows(IllegalArgumentException.class,()->documents.upload(visa.getId(),"Invalid link",pdf(),requiredId(second)));
        assertEquals(count,documentRepo.count()); login(other);
        assertThrows(AccessDeniedException.class,()->documents.upload(visa.getId(),"Foreign upload",pdf(),requiredId(first)));
        assertThrows(AccessDeniedException.class,()->checklists.forVisa(visa.getId()));
        assertThrows(AccessDeniedException.class,()->checklists.forCustomerBooking(visa.getBooking().getId(),VisaType.VISIT));
    }
    @Test void unconfiguredExistingApplicationsRetainOfficerVerification() throws Exception {
        var visa=apply(create(false)); var old=documents.upload(visa.getId(),"Existing document",pdf()); publish("New required evidence",null);
        login(officer); visas.markDocumentsVerified(visa.getId());
        assertNull(visaRepo.findById(visa.getId()).orElseThrow().getChecklist());
        assertNull(documentRepo.findById(old.getId()).orElseThrow().getChecklistItem());
    }
    @Test void officerCanExplicitlyAttachAndAssignExistingEvidenceWithHistoryAndNotification() throws Exception {
        var visa=apply(create(false)); var old=documents.upload(visa.getId(),"Preserved old document",pdf()); var c=publish("Test evidence",null);
        long notices=notificationRepo.count(); checklists.attachPublished(visa.getId(),c.getId());
        assertEquals(notices+1,notificationRepo.count());
        assertTrue(historyRepo.findByVisaApplication_IdOrderByChangedAtAsc(visa.getId()).stream().anyMatch(h->h.getRemarks().contains("checklist version")));
        assertThrows(IllegalStateException.class,()->visas.markDocumentsVerified(visa.getId()));
        checklists.assignExistingDocument(old.getId(),requiredId(c));
        assertEquals(old.getDocumentUrl(),documentRepo.findById(old.getId()).orElseThrow().getDocumentUrl());
        assertThrows(IllegalStateException.class,()->checklists.assignExistingDocument(old.getId(),requiredId(c)));
        visas.markDocumentsVerified(visa.getId());
        assertThrows(IllegalStateException.class,()->checklists.attachPublished(visa.getId(),c.getId()));
    }
    @Test void configuredLeadTimeIsEnforcedAtApplicationWithExactBoundaryAllowed() {
        var c=publish("Test evidence",32); var booking=create(false);
        assertThrows(IllegalStateException.class,()->apply(booking)); assertTrue(visaRepo.findByBooking_Id(booking.getId()).isEmpty());
        login(officer); checklists.retire(c.getId(),c.getLockVersion()); publish("Test evidence",31);
        assertNotNull(apply(booking));
    }
    @Test void freshStoredRoleAndPermissionRequiredForManagement() {
        login(customer); assertThrows(AccessDeniedException.class,()->checklists.createDraft(request("Test evidence",null)));
        login(consultant); assertThrows(AccessDeniedException.class,checklists::findAll);
        login(admin); assertThrows(AccessDeniedException.class,()->checklists.createDraft(request("Test evidence",null)));
        var unpermittedRole=new Role(); unpermittedRole.setRoleName("ROLE_VISA_OFFICER"); roleRepo.save(unpermittedRole);
        var unpermitted=new User("No management permission","checklist-no-perm-"+java.util.UUID.randomUUID()+"@example.invalid","unused",unpermittedRole);userRepo.save(unpermitted);login(unpermitted);
        assertThrows(AccessDeniedException.class,()->checklists.createDraft(request("Test evidence",null)));
    }
    @Test void mvcRoleMatrixCsrfAndOwnerRequirementLookupAreEnforced() throws Exception {
        var booking=create(false);
        mvc.perform(get("/staff/visas/checklists").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/staff/visas/checklists").with(staffUser(consultant.getEmail(),"TRAVEL_CONSULTANT"))).andExpect(status().isForbidden());
        mvc.perform(post("/staff/visas/checklists").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(get("/customer/visas/requirements").param("bookingId",booking.getId().toString()).param("visaType","VISIT").with(user(other.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/customer/visas/requirements").param("bookingId",booking.getId().toString()).param("visaType","VISIT").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").doesNotExist());
    }
    @Test void mvcRendersChecklistFormsAndCustomerSnapshotWithEscapedLabels() throws Exception {
        var c=publish("Synthetic evidence <&>",null); var visa=apply(create(false));
        mvc.perform(get("/staff/visas/checklists").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Synthetic evidence &lt;&amp;&gt;")));
        mvc.perform(get("/staff/visas/checklists/new").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk()).andExpect(view().name("staff/visa-checklist-form"));
        mvc.perform(get("/staff/visas/checklists/"+c.getId()+"/edit").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk());
        mvc.perform(get("/customer/visas").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Checklist version 1")));
        mvc.perform(get("/staff/visas").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/staff/visas/checklists")));
    }
    @Test void concurrentDraftsAndPublicationsSerializeWithOneCurrentRevision() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var ready=new java.util.concurrent.CountDownLatch(2); var go=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<VisaChecklist> work=()->{
                login(officer); ready.countDown();
                try { assertTrue(go.await(20,java.util.concurrent.TimeUnit.SECONDS)); return checklists.createDraft(request("Concurrent test evidence",null)); }
                finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            };
            var one=pool.submit(work); var two=pool.submit(work); assertTrue(ready.await(20,java.util.concurrent.TimeUnit.SECONDS)); go.countDown();
            var first=one.get(30,java.util.concurrent.TimeUnit.SECONDS); var second=two.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(first.getRevision(),second.getRevision());
            var publishedReady=new java.util.concurrent.CountDownLatch(2); var publishGo=new java.util.concurrent.CountDownLatch(1);
            var publishOne=pool.submit(()->{ login(officer);publishedReady.countDown();try { publishGo.await();return checklists.publish(first.getId(),first.getLockVersion()); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
            var publishTwo=pool.submit(()->{ login(officer);publishedReady.countDown();try { publishGo.await();return checklists.publish(second.getId(),second.getLockVersion()); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
            assertTrue(publishedReady.await(20,java.util.concurrent.TimeUnit.SECONDS)); publishGo.countDown();
            publishOne.get(30,java.util.concurrent.TimeUnit.SECONDS); publishTwo.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,checklistRepo.findByDestination_IdAndVisaTypeAndStatus(tour.getDestination().getId(),VisaType.VISIT,VisaChecklist.Status.PUBLISHED).size());
            assertEquals(1,checklistRepo.findByDestination_IdAndVisaTypeAndStatus(tour.getDestination().getId(),VisaType.VISIT,VisaChecklist.Status.RETIRED).size());
        } finally { pool.shutdownNow(); }
    }
}
