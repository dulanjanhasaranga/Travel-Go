package com.travelgo.controller;
import com.travelgo.entity.TravelPolicy;
import com.travelgo.service.TravelPolicyService;
import org.springframework.web.bind.annotation.*;
@ControllerAdvice
public class AgencyPolicyAdvice {
    private final TravelPolicyService policies;
    private final java.time.Clock clock;
    public AgencyPolicyAdvice(TravelPolicyService policies,java.time.Clock clock){this.policies=policies;this.clock=clock;}
    @ModelAttribute("currentTravelPolicy") public TravelPolicy policy(){return policies.current().orElse(null);}
    @ModelAttribute("agencyTimezone") public String timezone(){return clock.getZone().getId();}
}
