package com.travelgo.controller;

import com.travelgo.service.DemoAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DemoLoginController {
    private final DemoAccountService accounts;
    private final SecurityContextRepository contexts;
    private final CompositeSessionAuthenticationStrategy sessions;

    public DemoLoginController(DemoAccountService accounts, SecurityContextRepository contexts,
                               CsrfTokenRepository csrfTokens) {
        this.accounts = accounts; this.contexts = contexts;
        this.sessions = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
    }

    @PostMapping("/auth/demo-login")
    public String login(@RequestParam String account, HttpServletRequest request,
                        HttpServletResponse response, RedirectAttributes flash) {
        DemoAccountService.Login login;
        try {
            login = accounts.authenticate(account);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() != 409) throw ex;
            flash.addFlashAttribute("errorMessage", ex.getReason());
            return "redirect:/auth/login";
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                login.principal(), null, login.principal().getAuthorities());
        sessions.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute("accountPasswordVersion", login.principal().getPassword());
        request.getSession().removeAttribute("permissionHash");
        new HttpSessionRequestCache().removeRequest(request, response);
        return "redirect:" + login.destination();
    }
}
