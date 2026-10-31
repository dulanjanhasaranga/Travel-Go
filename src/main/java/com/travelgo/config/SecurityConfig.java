package com.travelgo.config;

import com.travelgo.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Spring Security configuration.
 * - Public pages accessible without login
 * - Admin pages restricted to ADMIN role
 * - Customer pages restricted to CUSTOMER role
 * - Staff pages restricted to TRAVEL_CONSULTANT and VISA_OFFICER roles
 * - Session-based authentication with form login
 * - BCrypt password encoding
 * - @EnableMethodSecurity enables @PreAuthorize for fine-grained permission checks
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final com.travelgo.service.CustomOAuth2UserService customOAuth2UserService;
    private final com.travelgo.repository.UserRepository userRepository;
    private final boolean googleLoginEnabled;

    public SecurityConfig(CustomUserDetailsService userDetailsService, com.travelgo.repository.UserRepository userRepository,
                          com.travelgo.service.CustomOAuth2UserService customOAuth2UserService,
                          @Value("${travelgo.auth.google.enabled:false}") boolean googleLoginEnabled) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.googleLoginEnabled = googleLoginEnabled;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, com.travelgo.repository.UserRepository users,
                                           DemoMode demoMode,
                                           org.springframework.security.web.context.SecurityContextRepository contexts,
                                           org.springframework.security.web.csrf.CsrfTokenRepository csrfTokens,
                                           AuthenticationAbuseFilter authenticationAbuseFilter,
                                           ContentSecurityPolicyFilter contentSecurityPolicyFilter,
                                           StaffMfaGateFilter staffMfaGateFilter) throws Exception {
        http
            .addFilterBefore(contentSecurityPolicyFilter, org.springframework.security.web.header.HeaderWriterFilter.class)
            .addFilterBefore(authenticationAbuseFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(staffMfaGateFilter, org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            .addFilterBefore(new AccountSessionFilter(users, demoMode), StaffMfaGateFilter.class)
            .securityContext(context -> context.securityContextRepository(contexts))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).ignoringRequestMatchers(
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/webhooks/stripe"),
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/webhooks/payhere"),
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/webhooks/email/brevo")))
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/healthz").permitAll()
                // Public pages - accessible without login
                .requestMatchers(
                    "/", "/destinations", "/destinations/**", "/packages", "/visa-info",
                    "/about", "/contact", "/faq", "/maintenance", "/error",
                    "/terms", "/privacy", "/cancellation-policy", "/policies/**"
                ).permitAll()

                // Static resources
                .requestMatchers("/css/**", "/js/**", "/vendor/**", "/images/**", "/video/**", "/static/**").permitAll()
                .requestMatchers("/uploads/**").denyAll()
                .requestMatchers("/visa-documents/**").permitAll() // Controller returns uniform 404 after ownership check.

                // Authentication pages
                .requestMatchers("/auth/mfa", "/auth/mfa/**").authenticated()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/webhooks/stripe", "/api/webhooks/payhere", "/webhooks/email/brevo").permitAll()

                // Staff Package Management pages
                .requestMatchers(HttpMethod.GET, "/staff/destinations/**", "/staff/hotels/**", "/staff/transports/**", "/staff/categories/**").access(rolePermissions("TRAVEL_CONSULTANT", "PERM_DESTINATION_VIEW", "PERM_DESTINATION_MANAGE"))
                .requestMatchers("/staff/destinations/**", "/staff/hotels/**", "/staff/transports/**", "/staff/categories/**").access(rolePermissions("TRAVEL_CONSULTANT", "PERM_DESTINATION_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/staff/packages/**").access(rolePermissions("TRAVEL_CONSULTANT", "PERM_PACKAGE_VIEW", "PERM_PACKAGE_MANAGE"))
                .requestMatchers("/staff/packages/**").access(rolePermissions("TRAVEL_CONSULTANT", "PERM_PACKAGE_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/staff/bookings/**", "/staff/inquiries/**").access(rolePermissions("TRAVEL_CONSULTANT", "PERM_BOOKING_VIEW", "PERM_BOOKING_MANAGE"))
                .requestMatchers("/staff/bookings/**", "/staff/inquiries/**").access(rolePermissions("TRAVEL_CONSULTANT", "PERM_BOOKING_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/staff/visas/**").access(rolePermissions("VISA_OFFICER", "PERM_VISA_VIEW", "PERM_VISA_MANAGE"))
                .requestMatchers("/staff/visas/**").access(rolePermissions("VISA_OFFICER", "PERM_VISA_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/staff/payments/**").access(rolePermissions("VISA_OFFICER", "PERM_VISA_MANAGE", "PERM_PAYMENT_MANAGE", "PERM_PAYMENT_VIEW"))
                .requestMatchers("/staff/payments/**").access(rolePermissions("VISA_OFFICER", "PERM_VISA_MANAGE", "PERM_PAYMENT_MANAGE"))
                .requestMatchers("/staff/reports/**").hasRole("ADMIN")

                // Admin pages
                .requestMatchers(HttpMethod.GET, "/admin/users/**").access(rolePermissions("ADMIN", "PERM_USER_VIEW", "PERM_USER_MANAGE"))
                .requestMatchers("/admin/users/**").access(rolePermissions("ADMIN", "PERM_USER_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/admin/staff/**").access(rolePermissions("ADMIN", "PERM_STAFF_VIEW", "PERM_STAFF_MANAGE"))
                .requestMatchers("/admin/staff/**").access(rolePermissions("ADMIN", "PERM_STAFF_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/admin/roles/**").access(rolePermissions("ADMIN", "PERM_ROLE_VIEW", "PERM_ROLE_MANAGE"))
                .requestMatchers("/admin/roles/**").access(rolePermissions("ADMIN", "PERM_ROLE_MANAGE"))
                .requestMatchers(HttpMethod.GET, "/admin/permissions/**").access(rolePermissions("ADMIN", "PERM_PERMISSION_VIEW"))
                .requestMatchers("/admin/permissions/**").hasRole("ADMIN") // Only admins can theoretically manage permissions
                .requestMatchers(HttpMethod.GET, "/admin/settings/**").access(rolePermissions("ADMIN", "PERM_SYSTEM_SETTINGS_VIEW", "PERM_SYSTEM_SETTINGS_MANAGE"))
                .requestMatchers("/admin/settings/**").access(rolePermissions("ADMIN", "PERM_SYSTEM_SETTINGS_MANAGE"))
                .requestMatchers("/admin/dashboard", "/admin").hasRole("ADMIN")
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // Customer pages - CUSTOMER role only
                .requestMatchers("/customer/**", "/packages/*/book").hasRole("CUSTOMER")
                .requestMatchers("/packages/*").permitAll()

                // Staff dashboard - accessible to any staff role
                .requestMatchers("/staff/dashboard", "/staff").hasAnyRole("TRAVEL_CONSULTANT", "VISA_OFFICER", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/ws-travelgo", "/ws-travelgo/**").hasAnyRole("TRAVEL_CONSULTANT", "VISA_OFFICER", "ADMIN")
                .requestMatchers("/ws-travelgo", "/ws-travelgo/**").denyAll()
                // All other /staff/** paths are gated by the PERM_* rules above;
                // this catch-all requires authentication so unauthenticated users are redirected to login.
                .requestMatchers("/staff/**").authenticated()

                // All other requests require authentication
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/auth/login")
                .loginProcessingUrl("/auth/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(customSuccessHandler())
                .failureUrl("/auth/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/auth/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .exceptionHandling(ex -> ex
                .accessDeniedPage("/auth/access-denied")
            );

        if (googleLoginEnabled) {
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/auth/login")
                    .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService)
                            .oidcUserService(customOAuth2UserService::loadOidcUser))
                    .successHandler(customSuccessHandler())
            );
        }

        return http.build();
    }

    private static org.springframework.security.authorization.AuthorizationManager<org.springframework.security.web.access.intercept.RequestAuthorizationContext>
            rolePermissions(String role, String... permissions) {
        return org.springframework.security.authorization.AuthorizationManagers.allOf(
                org.springframework.security.authorization.AuthorityAuthorizationManager.<org.springframework.security.web.access.intercept.RequestAuthorizationContext>hasRole(role),
                org.springframework.security.authorization.AuthorityAuthorizationManager.<org.springframework.security.web.access.intercept.RequestAuthorizationContext>hasAnyAuthority(permissions));
    }

    @Bean
    public org.springframework.security.web.context.SecurityContextRepository securityContextRepository() {
        return new org.springframework.security.web.context.DelegatingSecurityContextRepository(
                new org.springframework.security.web.context.RequestAttributeSecurityContextRepository(),
                new org.springframework.security.web.context.HttpSessionSecurityContextRepository());
    }

    @Bean
    public org.springframework.security.web.csrf.CsrfTokenRepository csrfTokenRepository() {
        return new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
    }

    /**
     * Customers return to the public home page or their saved booking form;
     * staff and administrators open their role-specific workspace.
     */
    @Bean
    public AuthenticationSuccessHandler customSuccessHandler() {
        return (request, response, authentication) -> {
            com.travelgo.security.StaffMfaSession.clear(request.getSession());
            request.getSession().setAttribute(com.travelgo.security.StaffMfaSession.PASSWORD_AT, java.time.Instant.now());
            request.getSession().setAttribute("accountPasswordVersion", userDetailsService.loadUserByUsername(authentication.getName()).getPassword());
            // Store the permission hash so that AccountSessionFilter can detect mid-session permission changes
            userRepository.findByEmailIgnoreCase(authentication.getName()).ifPresent(user ->
                request.getSession().setAttribute("permissionHash",
                    com.travelgo.service.CustomUserDetailsService.permissionHash(user.getRole().getPermissions()))
            );
            String role = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst().orElse("");

            var requestCache = new org.springframework.security.web.savedrequest.HttpSessionRequestCache();
            var savedRequest = requestCache.getRequest(request, response);
            String bookingReturn = "ROLE_CUSTOMER".equals(role) ? savedBookingPath(savedRequest, request) : null;
            requestCache.removeRequest(request, response);

            switch (role) {
                case "ROLE_ADMIN":
                    response.sendRedirect("/admin/dashboard");
                    break;
                case "ROLE_CUSTOMER":
                    response.sendRedirect(bookingReturn != null ? bookingReturn : "/");
                    break;
                case "ROLE_TRAVEL_CONSULTANT":
                case "ROLE_VISA_OFFICER":
                    response.sendRedirect("/staff/dashboard");
                    break;
                default:
                    response.sendRedirect("/auth/login?roleUnavailable=true");
                    break;
            }
        };
    }

    /** Only a same-origin saved booking GET can replace the customer's normal landing page. */
    private static String savedBookingPath(org.springframework.security.web.savedrequest.SavedRequest saved,
                                           jakarta.servlet.http.HttpServletRequest request) {
        if (saved == null || !"GET".equals(saved.getMethod()) || saved.getRedirectUrl() == null) return null;
        try {
            var url = java.net.URI.create(saved.getRedirectUrl());
            String path = url.getRawPath();
            int port = url.getPort() >= 0 ? url.getPort() : "https".equalsIgnoreCase(url.getScheme()) ? 443 : 80;
            if (!request.getScheme().equalsIgnoreCase(url.getScheme())
                    || !request.getServerName().equalsIgnoreCase(url.getHost()) || request.getServerPort() != port
                    || url.getRawUserInfo() != null || url.getRawFragment() != null
                    || path == null || !path.matches("/packages/[1-9][0-9]*/book")) return null;
            // Spring's cache appends its own `continue` marker. Browser-supplied query parameters are never followed.
            if (url.getRawQuery() != null && !url.getRawQuery().equals("continue")) return null;
            Long.parseLong(path.substring("/packages/".length(), path.length() - "/book".length()));
            return path;
        } catch (IllegalArgumentException invalidSavedUrl) {
            return null;
        }
    }
}

