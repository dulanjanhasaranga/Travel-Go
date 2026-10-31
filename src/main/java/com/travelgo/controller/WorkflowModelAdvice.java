package com.travelgo.controller;
import java.time.*;
import org.springframework.web.bind.annotation.*;
@ControllerAdvice
public class WorkflowModelAdvice {
    private final Clock clock;
    private final com.travelgo.service.UserService users;
    public WorkflowModelAdvice(Clock clock, com.travelgo.service.UserService users) { this.clock=clock; this.users=users; }
    @ModelAttribute("workflowNow") public LocalDateTime now() { return LocalDateTime.now(clock); }
    @ModelAttribute("registrationAvailable") public boolean registrationAvailable() { return users.isEmailVerificationConfigured(); }
}
