package com.travelgo.controller;
import java.time.*;
import org.springframework.web.bind.annotation.*;
@ControllerAdvice
public class WorkflowModelAdvice {
    private final Clock clock;
    public WorkflowModelAdvice(Clock clock) { this.clock=clock; }
    @ModelAttribute("workflowNow") public LocalDateTime now() { return LocalDateTime.now(clock); }
}
