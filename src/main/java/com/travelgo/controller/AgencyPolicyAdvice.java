package com.travelgo.controller;
import com.travelgo.entity.TravelPolicy;
import com.travelgo.service.RequestPageLayout;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
@ControllerAdvice
public class AgencyPolicyAdvice {
    private final RequestPageLayout layout;
    private final java.time.Clock clock;
    public AgencyPolicyAdvice(RequestPageLayout layout,java.time.Clock clock){this.layout=layout;this.clock=clock;}
    @ModelAttribute("currentTravelPolicy") public TravelPolicy policy(HttpServletRequest request){
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        // JSON endpoints and provider callbacks have no page footer to populate.
        if (handler instanceof HandlerMethod method &&
                (AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ResponseBody.class)
                || method.hasMethodAnnotation(ResponseBody.class))) return null;
        return layout.get().policy();
    }
    @ModelAttribute("agencyTimezone") public String timezone(){return clock.getZone().getId();}
}
