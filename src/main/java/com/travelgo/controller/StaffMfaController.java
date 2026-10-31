package com.travelgo.controller;

import com.travelgo.config.StaffMfaGateFilter;
import com.travelgo.security.StaffMfaSession;
import com.travelgo.service.StaffMfaService;
import jakarta.servlet.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/auth/mfa")
@PreAuthorize("hasAnyRole('ADMIN','TRAVEL_CONSULTANT','VISA_OFFICER')")
public class StaffMfaController {
    private final StaffMfaService mfa;
    public StaffMfaController(StaffMfaService mfa) { this.mfa = mfa; }
    @GetMapping
    public String challenge(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam(defaultValue = "false") boolean stepUp, Model model) {
        StaffMfaGateFilter.noStore(response);
        return page(authentication, request, model, stepUp);
    }
    @PostMapping("/enroll")
    public String enroll(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String password, Model model) {
        StaffMfaGateFilter.noStore(response);
        StaffMfaService.Result result = mfa.beginEnrollment(authentication, request.getSession(false), password);
        if (!result.success()) reject(result, model, response);
        return page(authentication, request, model, false);
    }
    @PostMapping("/confirm")
    public String confirm(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String password, @RequestParam String code, Model model) {
        StaffMfaGateFilter.noStore(response);
        StaffMfaService.Result result = mfa.confirmEnrollment(authentication, request.getSession(false), password, code);
        if (!result.success()) { reject(result, model, response); return page(authentication, request, model, false); }
        established(request, result);
        model.addAttribute("recoveryCodes", result.recoveryCodes());
        model.addAttribute("workspacePath", workspace(authentication));
        return "auth/mfa-recovery-codes";
    }
    @PostMapping("/verify")
    public String verify(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String code, @RequestParam(defaultValue = "false") boolean recoveryCode, Model model) {
        StaffMfaGateFilter.noStore(response);
        StaffMfaService.Result result = mfa.verify(authentication, request.getSession(false), code, recoveryCode);
        if (!result.success()) { reject(result, model, response); return page(authentication, request, model, false); }
        established(request, result); return "redirect:" + workspace(authentication);
    }
    @PostMapping("/reauthenticate")
    public String reauthenticate(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String password, @RequestParam String code,
            @RequestParam(defaultValue = "false") boolean recoveryCode, Model model) {
        StaffMfaGateFilter.noStore(response);
        StaffMfaService.Result result = mfa.reauthenticate(authentication, request.getSession(false), password, code, recoveryCode);
        if (!result.success()) { reject(result, model, response); return page(authentication, request, model, true); }
        established(request, result); return "redirect:" + workspace(authentication);
    }
    @PostMapping("/recovery-codes/regenerate")
    public String regenerate(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String password, @RequestParam String code,
            @RequestParam(defaultValue = "false") boolean recoveryCode, Model model) {
        StaffMfaGateFilter.noStore(response);
        StaffMfaService.Result result = mfa.regenerateRecoveryCodes(authentication, request.getSession(false), password, code, recoveryCode);
        if (!result.success()) { reject(result, model, response); return page(authentication, request, model, true); }
        established(request, result); model.addAttribute("recoveryCodes", result.recoveryCodes());
        model.addAttribute("workspacePath", workspace(authentication)); return "auth/mfa-recovery-codes";
    }
    @PostMapping("/authenticator/replace")
    public String replaceAuthenticator(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String password, @RequestParam String code,
            @RequestParam(defaultValue = "false") boolean recoveryCode, Model model) {
        StaffMfaGateFilter.noStore(response);
        StaffMfaService.Result result = mfa.replaceAuthenticator(authentication, request.getSession(false), password, code, recoveryCode);
        if (!result.success()) { reject(result, model, response); return page(authentication, request, model, true); }
        request.changeSessionId(); request.getSession().removeAttribute(StaffMfaSession.PROOF);
        request.getSession().removeAttribute(StaffMfaSession.PASSWORD_AT);
        return page(authentication, request, model, false);
    }
    private String page(Authentication authentication, HttpServletRequest request, Model model, boolean stepUp) {
        var status = mfa.status(authentication, request.getSession(false));
        if (!mfa.isEnabled()) throw new org.springframework.security.access.AccessDeniedException("Staff verification is not available.");
        model.addAttribute("mfaEnrolled", status.enrolled()); model.addAttribute("mfaVerified", status.verified());
        model.addAttribute("stepUp", stepUp); model.addAttribute("workspacePath", workspace(authentication));
        if (!status.enrolled()) mfa.setup(authentication, request.getSession(false)).ifPresent(setup -> model.addAttribute("mfaSetup", setup));
        return "auth/mfa";
    }
    private void reject(StaffMfaService.Result result, Model model, HttpServletResponse response) {
        response.setStatus(result.blocked() ? 429 : 400);
        model.addAttribute("mfaError", result.blocked()
                ? "Too many attempts. Please wait a few minutes before trying again."
                : "We could not verify those details. Use a new code and check your password if requested.");
    }
    private void established(HttpServletRequest request, StaffMfaService.Result result) {
        // Authentication already exists. Rotate the identifier again when the second factor or step-up succeeds.
        request.changeSessionId(); request.getSession().setAttribute(StaffMfaSession.PROOF, result.proof());
        request.getSession().removeAttribute(StaffMfaSession.PASSWORD_AT);
        request.getSession().removeAttribute(StaffMfaSession.ENROLLMENT);
    }
    private String workspace(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "/admin/dashboard" : "/staff/dashboard";
    }
}
