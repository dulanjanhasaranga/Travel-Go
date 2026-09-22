package com.travelgo.service;

import com.travelgo.entity.ContactMessage;
import com.travelgo.repository.ContactMessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ContactMessageService {

    private final ContactMessageRepository repository;
    private final com.travelgo.repository.NotificationRepository notifications;
    private final WorkflowRules rules;
    private final com.travelgo.repository.UserRepository users;
    private final com.travelgo.repository.DestinationRepository destinations;
    private final com.travelgo.repository.TourPackageRepository packages;
    private final TransactionalEmailService email;
    private final SystemSettingsService settings;
    private final jakarta.validation.Validator validator;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    public ContactMessageService(ContactMessageRepository repository, com.travelgo.repository.NotificationRepository notifications, WorkflowRules rules,com.travelgo.repository.UserRepository users,com.travelgo.repository.DestinationRepository destinations,com.travelgo.repository.TourPackageRepository packages,TransactionalEmailService email,SystemSettingsService settings,jakarta.validation.Validator validator) {
        this.repository = repository;
        this.notifications=notifications;this.rules=rules;this.users=users;this.destinations=destinations;this.packages=packages;this.email=email;this.settings=settings;this.validator=validator;
    }

    public List<ContactMessage> findAll() {
        return repository.findAll();
    }

    public Optional<ContactMessage> findById(Long id) {
        return repository.findById(id);
    }

    public java.util.List<ContactMessage> forCustomer(){var user=rules.actor();rules.requireRole("CUSTOMER");return repository.findByUser_IdOrderByCreatedAtDesc(user.getId());}
    public void requireManager(){var u=rules.actor();if(!rules.role(u,"ADMIN")&&!rules.role(u,"TRAVEL_CONSULTANT"))throw new org.springframework.security.access.AccessDeniedException("Inquiry management access required.");}
    @org.springframework.transaction.annotation.Transactional
    public ContactMessage submit(com.travelgo.dto.InquiryRequest request){
        if(!validator.validate(request).isEmpty())throw new IllegalArgumentException("Check the required fields and travel preferences.");
        if(request.preferredDate()!=null&&request.preferredDate().isBefore(rules.now().toLocalDate()))throw new IllegalArgumentException("Choose today or a future travel date.");
        if(request.submissionKey()==null||!request.submissionKey().matches("[a-zA-Z0-9-]{16,64}"))throw new IllegalArgumentException("Refresh the inquiry form and try again.");
        if(repository.findBySubmissionKey(request.submissionKey()).isPresent())throw new IllegalArgumentException("This inquiry was already received. Please check your confirmation.");
        var m=new ContactMessage();m.setSenderName(request.senderName().trim());m.setSenderEmail(request.senderEmail().trim());m.setSubject(request.subject().trim());m.setMessage(request.message().trim());m.setPhone(request.phone());m.setInquiryType(request.inquiryType()==null?"GENERAL":request.inquiryType());m.setPreferredDate(request.preferredDate());m.setTravelerCount(request.travelerCount());m.setBudgetRange(request.budgetRange());m.setSubmissionKey(request.submissionKey());
        if(request.destinationId()!=null)m.setDestination(destinations.findById(request.destinationId()).filter(com.travelgo.entity.Destination::isActive).orElseThrow(()->new IllegalArgumentException("Select an available destination.")));
        if(request.packageId()!=null){var p=packages.findById(request.packageId()).filter(p1->p1.isActive()&&p1.getDestination().isActive()).orElseThrow(()->new IllegalArgumentException("Select an available package."));if(m.getDestination()!=null&&!p.getDestination().getId().equals(m.getDestination().getId()))throw new IllegalArgumentException("Package and destination must match.");m.setTourPackage(p);m.setDestination(p.getDestination());}
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.isAuthenticated())users.findByEmail(auth.getName()).ifPresent(m::setUser);
        repository.saveAndFlush(m);
        var rows=new java.util.LinkedHashMap<String,String>();rows.put("Inquiry reference",m.getReference());rows.put("Subject",m.getSubject());rows.put("Travel date",m.getPreferredDate()==null?"To be discussed":m.getPreferredDate().toString());rows.put("Destination",m.getDestination()==null?"To be discussed":m.getDestination().getCity());rows.put("Package",m.getTourPackage()==null?"Tailored advice":m.getTourPackage().getName());rows.put("Message",m.getMessage());
        var s=settings.getSettings();email.queue("inquiry:"+m.getId(),m.getSenderEmail(),"Inquiry received — "+m.getReference()+" | TravelGO",java.util.Map.of("heading","Your inquiry is with our travel team","customerName",m.getSenderName(),"intro","Thank you for sharing your plans. We have saved your inquiry.","details",rows,"nextStep","Our team will review your preferences and contact you. Keep this reference for follow-up.","contact",s.getCompanyEmail()+" · "+s.getCompanyPhone()));
        users.findAll().stream().filter(u->u.isActive()&&(rules.role(u,"ADMIN")||rules.role(u,"TRAVEL_CONSULTANT"))).forEach(u->{var n=new com.travelgo.entity.Notification();n.setUser(u);n.setType("SUPPORT_RECEIVED");n.setRelatedEntityType("INQUIRY");n.setRelatedEntityId(m.getId());n.setTitle("New inquiry "+m.getReference());n.setMessage("A travel inquiry is ready in inquiry management.");notifications.save(n);});
        return m;
    }
    @org.springframework.transaction.annotation.Transactional
    public void updateStatus(Long id,com.travelgo.enums.ContactMessageStatus next,String notes,Long version){
        requireManager();var m=em.find(ContactMessage.class,id,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);if(m==null)throw new IllegalArgumentException("Inquiry not found.");
        if(!java.util.Objects.equals(version,m.getVersion()))throw new IllegalStateException("Inquiry changed. Refresh before updating.");
        if(notes!=null&&notes.length()>5000)throw new IllegalArgumentException("Notes must be at most 5,000 characters.");
        if(next==null)throw new IllegalArgumentException("Select a status.");
        if(next!=m.getStatus()){
          boolean allowed=switch(m.getStatus()){case OPEN->next==com.travelgo.enums.ContactMessageStatus.IN_PROGRESS||next==com.travelgo.enums.ContactMessageStatus.CLOSED;case IN_PROGRESS->next==com.travelgo.enums.ContactMessageStatus.CONTACTED||next==com.travelgo.enums.ContactMessageStatus.RESOLVED||next==com.travelgo.enums.ContactMessageStatus.CLOSED;case CONTACTED->next==com.travelgo.enums.ContactMessageStatus.RESOLVED||next==com.travelgo.enums.ContactMessageStatus.CLOSED;case RESOLVED->next==com.travelgo.enums.ContactMessageStatus.CLOSED||next==com.travelgo.enums.ContactMessageStatus.IN_PROGRESS;case CLOSED->next==com.travelgo.enums.ContactMessageStatus.IN_PROGRESS;};
          if(!allowed)throw new IllegalArgumentException("Move new inquiries into review before contacting or resolving them.");
        }m.setStatus(next);m.setInternalNotes(notes);m.setAssignedTo(rules.actor());
    }

    public ContactMessage save(ContactMessage entity) {
        if(entity.getSenderName()==null||entity.getSenderName().isBlank()||entity.getSenderName().length()>100||entity.getSenderEmail()==null||!entity.getSenderEmail().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||entity.getSenderEmail().length()>150||entity.getSubject()==null||entity.getSubject().isBlank()||entity.getSubject().length()>200||entity.getMessage()==null||entity.getMessage().isBlank()||entity.getMessage().length()>5000)throw new IllegalArgumentException("Enter your name, a valid email, a subject and a message of up to 5,000 characters.");
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        repository.deleteById(id);
    }

    @org.springframework.transaction.annotation.Transactional
    public ContactMessage replyToMessage(Long messageId, String replyText) {
        requireManager();
        if(replyText==null||replyText.isBlank()||replyText.length()>5000)throw new IllegalArgumentException("Enter a reply of up to 5,000 characters.");
        ContactMessage msg = em.find(ContactMessage.class,messageId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(msg==null)throw new IllegalArgumentException("Contact message not found");
        if(replyText.equals(msg.getReplyText()))return msg;
        msg.setReplyText(replyText);
        msg.setRepliedAt(java.time.LocalDateTime.now());
        msg.setStatus(com.travelgo.enums.ContactMessageStatus.RESOLVED);
        if(msg.getUser()!=null){var notification=new com.travelgo.entity.Notification();notification.setUser(msg.getUser());notification.setType("SUPPORT_RESPONSE");notification.setRelatedEntityType("INQUIRY");notification.setRelatedEntityId(msg.getId());notification.setTitle("Reply to your inquiry #"+msg.getId());notification.setMessage(replyText);notifications.save(notification);}
        var s=settings.getSettings();
        email.queue("inquiry-reply:"+msg.getId(), msg.getSenderEmail(), "Reply to your inquiry — " + msg.getReference() + " | TravelGO",
            java.util.Map.of("heading", "Our team has replied to your inquiry",
                             "customerName", msg.getSenderName(),
                             "intro", "Here is our response to your inquiry:",
                             "details", java.util.Map.of("Our Reply", replyText, "Original Message", msg.getMessage()),
                             "nextStep", "If you have any further questions, please contact our travel team.",
                             "contact", s.getCompanyEmail() + " · " + s.getCompanyPhone()));
        return repository.save(msg);
    }
}
