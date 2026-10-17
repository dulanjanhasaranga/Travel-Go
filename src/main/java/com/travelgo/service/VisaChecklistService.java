package com.travelgo.service;

import com.travelgo.dto.VisaChecklistRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.VisaType;
import com.travelgo.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class VisaChecklistService {
    private final VisaChecklistRepository repository;
    private final DestinationRepository destinations;
    private final BookingRepository bookings;
    private final VisaApplicationRepository visas;
    private final VisaDocumentRepository documents;
    private final VisaStatusHistoryRepository history;
    private final AuditLogRepository audit;
    private final WorkflowRules rules;
    private final EntityManager em;
    private final Validator validator;
    public VisaChecklistService(VisaChecklistRepository repository, DestinationRepository destinations,
            BookingRepository bookings, VisaApplicationRepository visas, VisaDocumentRepository documents,
            VisaStatusHistoryRepository history, AuditLogRepository audit, WorkflowRules rules, EntityManager em, Validator validator) {
        this.repository=repository; this.destinations=destinations; this.bookings=bookings; this.visas=visas;
        this.documents=documents; this.history=history; this.audit=audit; this.rules=rules; this.em=em; this.validator=validator;
    }
    private User officer(boolean write) {
        rules.requireRole("VISA_OFFICER"); User actor=rules.actor();
        boolean permitted=actor.getRole().getPermissions().stream().anyMatch(p -> "VISA_MANAGE".equals(p.getPermissionName())
                || !write && "VISA_VIEW".equals(p.getPermissionName()));
        if (!permitted) throw new AccessDeniedException("Visa management permission is required.");
        return actor;
    }
    @Transactional(readOnly=true) public List<VisaChecklist> findAll() {
        officer(false); var all=repository.findAllByOrderByCreatedAtDescIdDesc();
        all.forEach(c -> { c.getDestination().getCity(); c.getItems().size(); c.getCreatedBy().getName(); }); return all;
    }
    @Transactional(readOnly=true) public List<Destination> activeDestinations() {
        officer(false); return destinations.findAll().stream().filter(Destination::isActive).toList();
    }
    @Transactional(readOnly=true) public VisaChecklist findForOfficer(Long id) {
        officer(false); var c=repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Checklist not found."));
        c.getDestination().getCity(); c.getItems().size(); return c;
    }
    /** Internal read used by booking/application validation. Never guesses requirements where none were published. */
    @Transactional(readOnly=true) public Optional<VisaChecklist> published(Long destinationId, VisaType type) {
        if (destinationId==null || type==null) return Optional.empty();
        var matches=repository.findByDestination_IdAndVisaTypeAndStatus(destinationId,type,VisaChecklist.Status.PUBLISHED);
        if (matches.size()>1) throw new IllegalStateException("More than one published visa checklist exists. A visa officer must reconcile these requirements.");
        matches.forEach(c -> c.getItems().size()); return matches.stream().findFirst();
    }
    @Transactional(readOnly=true) public Optional<Integer> minimumLeadDays(Long destinationId, VisaType type) {
        return published(destinationId,type).map(VisaChecklist::getMinimumLeadDays);
    }
    private Destination lockDestination(Long id) {
        Destination d=destinations.findById(id).orElseThrow(() -> new IllegalArgumentException("Destination not found."));
        em.refresh(d,LockModeType.PESSIMISTIC_WRITE); return d;
    }
    private VisaChecklist locked(Long id, Long expectedVersion) {
        var c=repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Checklist not found."));
        lockDestination(c.getDestination().getId()); em.refresh(c,LockModeType.PESSIMISTIC_WRITE);
        if (expectedVersion==null || !expectedVersion.equals(c.getLockVersion()))
            throw new IllegalStateException("This checklist changed. Reload it before making changes.");
        return c;
    }
    private static String clean(String value) { return value==null || value.isBlank() ? null : value.trim(); }
    private List<VisaChecklistItem> entries(VisaChecklistRequest r) {
        var result=new ArrayList<VisaChecklistItem>(); var seen=new HashSet<String>();
        for (boolean required : List.of(true,false)) {
            String text=required?r.requiredDocuments():r.optionalDocuments(); if (text==null) continue;
            for (String line : text.split("\\R")) {
                String label=line.trim(); if (label.isEmpty()) continue;
                if (label.length()>180 || label.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Enter a document name using 1 to 180 characters and no control characters.");
                if (!seen.add(label.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Use a different name for each document requirement.");
                var item=new VisaChecklistItem(); item.setLabel(label); item.setRequired(required); item.setPosition(result.size()); result.add(item);
            }
        }
        if (result.size()>30) throw new IllegalArgumentException("Add no more than 30 document requirements.");
        return result;
    }
    private void validate(VisaChecklistRequest r) {
        if (r==null || !validator.validate(r).isEmpty()) throw new IllegalArgumentException("Check the destination and visa category. Day values must be between 1 and 365, and text must stay within the field limits.");
        if (r.sourceReviewedOn()!=null && r.sourceReviewedOn().isAfter(rules.now().toLocalDate()))
            throw new IllegalArgumentException("The source review date cannot be in the future.");
        String source=clean(r.sourceUrl());
        if (source!=null) {
            try { URI uri=URI.create(source);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Enter a complete HTTPS source link without sign-in credentials or a section anchor."); }
        }
        if ((r.estimatedProcessingMinDays()==null)!=(r.estimatedProcessingMaxDays()==null)
                || r.estimatedProcessingMinDays()!=null && r.estimatedProcessingMinDays()>r.estimatedProcessingMaxDays())
            throw new IllegalArgumentException("Enter both processing estimate limits, with the minimum no greater than the maximum, or leave both blank.");
        entries(r);
    }
    private void populate(VisaChecklist c, VisaChecklistRequest r) {
        c.setSourceUrl(clean(r.sourceUrl())); c.setSourceReviewedOn(r.sourceReviewedOn()); c.setMinimumLeadDays(r.minimumLeadDays());
        c.setEstimatedProcessingMinDays(r.estimatedProcessingMinDays()); c.setEstimatedProcessingMaxDays(r.estimatedProcessingMaxDays());
        c.setGuidance(clean(r.guidance())); c.getItems().clear();
        for (var item:entries(r)) { item.setChecklist(c); c.getItems().add(item); }
    }
    private void record(String action,VisaChecklist c,User actor) {
        audit.save(new AuditLog(action,"VisaChecklist",c.getId(),actor.getEmail(),
                "Destination #"+c.getDestination().getId()+", "+c.getVisaType()+", revision "+c.getRevision()+", "+c.getStatus()));
    }
    public VisaChecklist createDraft(VisaChecklistRequest r) {
        User actor=officer(true); validate(r); Destination destination=lockDestination(r.destinationId());
        if (!destination.isActive()) throw new IllegalStateException("Select an active destination.");
        var c=new VisaChecklist(); c.setDestination(destination); c.setVisaType(r.visaType());
        c.setRevision(repository.findFirstByDestination_IdAndVisaTypeOrderByRevisionDesc(destination.getId(),r.visaType()).map(x->x.getRevision()+1).orElse(1));
        c.setCreatedBy(actor); c.setCreatedAt(rules.now()); populate(c,r); repository.saveAndFlush(c); record("CHECKLIST_DRAFT_CREATED",c,actor); return c;
    }
    public VisaChecklist updateDraft(Long id, Long expectedVersion, VisaChecklistRequest r) {
        User actor=officer(true); validate(r); var c=locked(id,expectedVersion);
        if (c.getStatus()!=VisaChecklist.Status.DRAFT) throw new IllegalStateException("Published checklists cannot be edited. Create a new draft version.");
        if (!c.getDestination().getId().equals(r.destinationId()) || c.getVisaType()!=r.visaType())
            throw new IllegalArgumentException("A checklist version must keep its destination and visa category. Create a new checklist to change them.");
        populate(c,r); c.recordEdit(); repository.saveAndFlush(c); record("CHECKLIST_DRAFT_UPDATED",c,actor); return c;
    }
    public VisaChecklist publish(Long id, Long expectedVersion) {
        User actor=officer(true); var c=locked(id,expectedVersion);
        if (c.getStatus()!=VisaChecklist.Status.DRAFT) throw new IllegalStateException("Only draft requirements can be published.");
        if (!c.getDestination().isActive() || c.getSourceUrl()==null || c.getSourceReviewedOn()==null || c.getItems().stream().noneMatch(VisaChecklistItem::isRequired))
            throw new IllegalStateException("Before publishing, select an active destination, add a reviewed official source and review date, and include at least one required document.");
        var previous=repository.findByDestination_IdAndVisaTypeAndStatus(c.getDestination().getId(),c.getVisaType(),VisaChecklist.Status.PUBLISHED);
        if (previous.size()>1) throw new IllegalStateException("More than one checklist is published for this destination and visa type. Review the versions before continuing.");
        for (var old:previous) { old.setStatus(VisaChecklist.Status.RETIRED); record("CHECKLIST_SUPERSEDED",old,actor); }
        c.setStatus(VisaChecklist.Status.PUBLISHED); c.setPublishedBy(actor); c.setPublishedAt(rules.now()); repository.saveAndFlush(c); record("CHECKLIST_PUBLISHED",c,actor); return c;
    }
    public void retire(Long id, Long expectedVersion) {
        User actor=officer(true); var c=locked(id,expectedVersion);
        if (c.getStatus()!=VisaChecklist.Status.PUBLISHED) throw new IllegalStateException("Only currently published requirements can be retired.");
        c.setStatus(VisaChecklist.Status.RETIRED); repository.saveAndFlush(c); record("CHECKLIST_RETIRED",c,actor);
    }
    public void checkLeadTime(Booking booking, VisaChecklist checklist) {
        if (checklist!=null && checklist.getMinimumLeadDays()!=null
                && booking.getTravelDate().isBefore(rules.now().toLocalDate().plusDays(checklist.getMinimumLeadDays())))
            throw new IllegalStateException("Start this visa application at least "+checklist.getMinimumLeadDays()+" days before travel. Contact a visa officer to review your travel date.");
    }
    public VisaChecklistItem resolveItem(VisaApplication visa, Long itemId) {
        if (itemId==null) return null;
        if (visa.getChecklist()==null) throw new IllegalArgumentException("This application has no published checklist. Leave the requirement selection blank.");
        return visa.getChecklist().getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select a requirement from this application's checklist."));
    }
    public void requireComplete(VisaApplication visa, List<VisaDocument> files) {
        if (visa.getChecklist()==null) return;
        Set<Long> uploaded=new HashSet<>(); files.stream().filter(d->d.getChecklistItem()!=null).forEach(d->uploaded.add(d.getChecklistItem().getId()));
        var missing=visa.getChecklist().getItems().stream().filter(VisaChecklistItem::isRequired).filter(i->!uploaded.contains(i.getId())).map(VisaChecklistItem::getLabel).toList();
        if (!missing.isEmpty()) throw new IllegalStateException("Required documents are missing: "+String.join(", ",missing)+". Match each uploaded file to its checklist requirement before verification.");
    }
    /** Explicit officer action for an existing application; never retroactively binds requirements on a read. */
    public void attachPublished(Long visaId, Long checklistId) {
        User actor=officer(true); var v=visas.findById(visaId).orElseThrow(() -> new IllegalArgumentException("Visa application not found."));
        rules.lock(v.getBooking().getId()); em.refresh(v); rules.requireDocumentsOpen(v);
        if (v.getChecklist()!=null) throw new IllegalStateException("This application already has an assigned checklist version and cannot be reassigned.");
        var c=repository.findById(checklistId).orElseThrow(() -> new IllegalArgumentException("Checklist not found."));
        lockDestination(c.getDestination().getId()); em.refresh(c);
        if (c.getStatus()!=VisaChecklist.Status.PUBLISHED || c.getVisaType()!=v.getVisaType()
                || !c.getDestination().getId().equals(v.getBooking().getTourPackage().getDestination().getId()))
            throw new IllegalArgumentException("Select the currently published checklist for this destination and visa category.");
        v.setChecklist(c); visas.save(v);
        var event=new VisaStatusHistory(); event.setVisaApplication(v); event.setStatus(v.getStatus()); event.setChangedBy(actor);
        event.setRemarks("Document checklist version "+c.getRevision()+" has been assigned. Existing files must be matched to the required documents before review."); history.save(event);
        rules.notify(v.getBooking().getUser(),"VISA_CHECKLIST_ASSIGNED","Visa document requirements confirmed",event.getRemarks(),"VISA",v.getId());
    }
    public void assignExistingDocument(Long documentId, Long itemId) {
        officer(true); var d=documents.findById(documentId).orElseThrow(() -> new IllegalArgumentException("Document not found."));
        var v=d.getVisaApplication(); rules.lock(v.getBooking().getId()); em.refresh(v); em.refresh(d); rules.requireDocumentsOpen(v);
        var item=resolveItem(v,itemId); if (item==null) throw new IllegalArgumentException("Select a checklist requirement.");
        if (d.getChecklistItem()!=null) throw new IllegalStateException("This document is already matched to a requirement. Upload a separate file for another requirement.");
        d.setChecklistItem(item); documents.save(d);
        audit.save(new AuditLog("VISA_DOCUMENT_REQUIREMENT_ASSIGNED","VisaDocument",d.getId(),rules.actor().getEmail(),"Checklist item #"+item.getId()));
    }
    public record Item(Long id,String label,boolean required,boolean uploaded) {}
    public record View(Long id,Integer revision,String sourceUrl,LocalDate sourceReviewedOn,Integer minimumLeadDays,
            Integer processingMinDays,Integer processingMaxDays,String guidance,List<Item> items) {}
    private View view(VisaChecklist checklist,List<VisaDocument> files) {
        if (checklist==null) return new View(null,null,null,null,null,null,null,null,List.of());
        var linked=files.stream().filter(d->d.getChecklistItem()!=null).map(d->d.getChecklistItem().getId()).toList();
        return new View(checklist.getId(),checklist.getRevision(),checklist.getSourceUrl(),checklist.getSourceReviewedOn(),
                checklist.getMinimumLeadDays(),checklist.getEstimatedProcessingMinDays(),checklist.getEstimatedProcessingMaxDays(),checklist.getGuidance(),
                checklist.getItems().stream().map(i->new Item(i.getId(),i.getLabel(),i.isRequired(),linked.contains(i.getId()))).toList());
    }
    @Transactional(readOnly=true) public View forVisa(Long visaId) {
        var v=visas.findById(visaId).orElseThrow(() -> new IllegalArgumentException("Visa application not found."));
        if (rules.role(rules.actor(),"CUSTOMER")) rules.owner(v.getBooking()); else officer(false);
        return view(v.getChecklist(),documents.findByVisaApplication_Id(visaId));
    }
    @Transactional(readOnly=true) public View forCustomerBooking(Long bookingId,VisaType type) {
        var b=bookings.findById(bookingId).orElseThrow(() -> new IllegalArgumentException("Booking not found.")); rules.owner(b);
        if (type==null) throw new IllegalArgumentException("Select a visa category.");
        return view(published(b.getTourPackage().getDestination().getId(),type).orElse(null),List.of());
    }
}
