package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("travelPolicies")
@Transactional(readOnly=true)
public class TravelPolicyService {
    private final TravelPolicyRepository policies;
    private final RoleRepository roles;
    private final AdminAccess access;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Value("${travelgo.bookings.require-published-policy:false}") private boolean required;
    public TravelPolicyService(TravelPolicyRepository policies,RoleRepository roles,AdminAccess access,Clock clock){
        this.policies=policies;this.roles=roles;this.access=access;this.clock=clock;
    }
    public Optional<TravelPolicy> current(){return policies.findFirstByPublishedTrueOrderByPublishedAtDescIdDesc();}
    public List<TravelPolicy> list(){access.requireAdministrator("SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_MANAGE");return policies.findAllByOrderByIdDesc();}
    public TravelPolicy published(Long id){return policies.findById(id).filter(TravelPolicy::isPublished).orElseThrow(()->new IllegalArgumentException("Published policy not found."));}
    @Transactional
    public TravelPolicy draft(String title,String terms,String privacy,String cancellation){
        User actor=access.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        TravelPolicy p=new TravelPolicy();p.setTitle(text(title,5,200,"Policy title"));
        p.setTerms(text(terms,80,20000,"Customer terms"));p.setPrivacy(text(privacy,80,20000,"Privacy policy"));
        p.setCancellation(text(cancellation,80,20000,"Cancellation and refund policy"));
        p.setCreatedAt(LocalDateTime.now(clock));p.setCreatedBy(actor);policies.saveAndFlush(p);
        access.record(actor,"POLICY_DRAFT_CREATED","TravelPolicy",p.getId(),"Draft policy version saved. It is not publicly active.");return p;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public TravelPolicy publish(Long id,Long expectedCurrentId,boolean agencyApproved){
        User actor=access.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        roles.lockByRoleName("ADMIN").orElseThrow(()->new IllegalStateException("Administrator role is unavailable."));
        TravelPolicy p=policies.findById(id).orElseThrow(()->new IllegalArgumentException("Policy not found."));
        em.refresh(p);
        if(p.isPublished()) return p;
        if(!agencyApproved)throw new IllegalArgumentException("Confirm that the agency has approved these policies before publishing.");
        if(!Objects.equals(current().map(TravelPolicy::getId).orElse(null),expectedCurrentId))
            throw new IllegalStateException("The published policy has changed. Refresh and review it before publishing.");
        LocalDateTime publishedAt=LocalDateTime.now(clock);
        var previous=current();if(previous.isPresent()&&!publishedAt.isAfter(previous.get().getPublishedAt()))publishedAt=previous.get().getPublishedAt().plusNanos(1000);
        p.setPublished(true);p.setPublishedAt(publishedAt);p.setPublishedBy(actor);policies.saveAndFlush(p);
        access.record(actor,"POLICY_PUBLISHED","TravelPolicy",p.getId(),"Agency-approved immutable policy version published.");return p;
    }
    public void validateAcceptance(Booking booking,Boolean accepted,Long policyId){
        var current=current();
        if(current.isEmpty()){if(required)throw new IllegalStateException("The agency must publish approved booking policies before accepting new bookings.");return;}
        if(!Boolean.TRUE.equals(accepted)||!current.get().getId().equals(policyId))
            throw new IllegalArgumentException("Review and accept the current booking policies. Refresh the form if a newer version was published.");
        booking.setAcceptedPolicy(current.get());booking.setPolicyAcceptedAt(LocalDateTime.now(clock));
    }
    private String text(String value,int min,int max,String label){
        if(value==null||value.trim().length()<min||value.trim().length()>max||value.indexOf('\0')>=0)
            throw new IllegalArgumentException(label+" must contain between "+min+" and "+max+" characters.");return value.trim();
    }
}
