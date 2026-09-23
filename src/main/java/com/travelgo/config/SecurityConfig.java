package com.travelgo.config;

import com.travelgo.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    public SecurityConfig(CustomUserDetailsService userDetailsService, com.travelgo.repository.UserRepository userRepository, com.travelgo.service.CustomOAuth2UserService customOAuth2UserService) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
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
                                           org.springframework.security.web.csrf.CsrfTokenRepository csrfTokens) throws Exception {
        http
            .addFilterBefore(new AccountSessionFilter(users, demoMode), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            .securityContext(context -> context.securityContextRepository(contexts))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                // Public pages - accessible without login
                .requestMatchers(
                    "/", "/destinations", "/destinations/**", "/packages", "/visa-info",
                    "/about", "/contact", "/faq", "/maintenance", "/error"
                ).permitAll()

                // Static resources
                .requestMatchers("/css/**", "/js/**", "/images/**", "/static/**").permitAll()
                .requestMatchers("/uploads/**").denyAll()
                .requestMatchers("/visa-documents/**").permitAll() // Controller returns uniform 404 after ownership check.

                // Authentication pages
                .requestMatchers("/auth/**").permitAll()

                // Staff Package Management pages
                .requestMatchers("/staff/destinations/**", "/staff/hotels/**", "/staff/categories/**").hasAnyAuthority("ROLE_TRAVEL_CONSULTANT", "PERM_DESTINATION_VIEW", "PERM_DESTINATION_MANAGE")
                .requestMatchers("/staff/packages/**").hasAnyAuthority("ROLE_TRAVEL_CONSULTANT", "PERM_PACKAGE_VIEW", "PERM_PACKAGE_MANAGE")
                .requestMatchers("/staff/bookings/**", "/staff/inquiries/**").hasAnyAuthority("ROLE_TRAVEL_CONSULTANT", "PERM_BOOKING_VIEW", "PERM_BOOKING_MANAGE")
                .requestMatchers("/staff/visas/**").hasAnyAuthority("ROLE_VISA_OFFICER", "PERM_VISA_VIEW", "PERM_VISA_MANAGE")
                .requestMatchers("/staff/payments/**").hasAnyAuthority("ROLE_VISA_OFFICER", "PERM_VISA_MANAGE", "PERM_PAYMENT_MANAGE", "PERM_PAYMENT_VIEW")
                .requestMatchers("/staff/reports/**").hasAnyAuthority("ROLE_TRAVEL_CONSULTANT", "ROLE_VISA_OFFICER", "PERM_BOOKING_VIEW", "PERM_VISA_VIEW")

                // Admin pages
                .requestMatchers("/admin/users/**").hasAnyAuthority("ROLE_ADMIN", "PERM_USER_VIEW", "PERM_USER_MANAGE")
                .requestMatchers("/admin/staff/**").hasAnyAuthority("ROLE_ADMIN", "PERM_STAFF_VIEW", "PERM_STAFF_MANAGE")
                .requestMatchers("/admin/roles/**").hasAnyAuthority("ROLE_ADMIN", "PERM_ROLE_VIEW", "PERM_ROLE_MANAGE")
                .requestMatchers("/admin/permissions/**").hasAnyAuthority("ROLE_ADMIN", "PERM_PERMISSION_VIEW")
                .requestMatchers("/admin/settings/**").hasAnyAuthority("ROLE_ADMIN", "PERM_SYSTEM_SETTINGS_VIEW", "PERM_SYSTEM_SETTINGS_MANAGE")
                .requestMatchers("/admin/dashboard", "/admin").hasAnyAuthority("ROLE_ADMIN", "PERM_DASHBOARD_VIEW")
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // Customer pages - CUSTOMER role only
                .requestMatchers("/customer/**", "/packages/*/book").hasRole("CUSTOMER")
                .requestMatchers("/packages/*").permitAll()

                // Staff dashboard and shared resources - TRAVEL_CONSULTANT or VISA_OFFICER
                .requestMatchers("/staff/dashboard", "/staff").hasAnyRole("TRAVEL_CONSULTANT", "VISA_OFFICER", "ADMIN")
                .requestMatchers("/staff/**").hasAnyRole("TRAVEL_CONSULTANT", "VISA_OFFICER", "ADMIN")

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
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/auth/login")
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(customSuccessHandler())
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/auth/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .exceptionHandling(ex -> ex
                .accessDeniedPage("/auth/login?denied=true")
            );

        return http.build();
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
     * Custom success handler that redirects users to their role-specific dashboard after login.
     */
    @Bean
    public AuthenticationSuccessHandler customSuccessHandler() {
        return (request, response, authentication) -> {
            request.getSession().setAttribute("accountPasswordVersion", userDetailsService.loadUserByUsername(authentication.getName()).getPassword());
            // Store the permission hash so that AccountSessionFilter can detect mid-session permission changes
            userRepository.findByEmail(authentication.getName()).ifPresent(user ->
                request.getSession().setAttribute("permissionHash",
                    com.travelgo.service.CustomUserDetailsService.permissionHash(user.getRole().getPermissions()))
            );
            String role = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst().orElse("");

            switch (role) {
                case "ROLE_ADMIN":
                    response.sendRedirect("/admin/dashboard");
                    break;
                case "ROLE_CUSTOMER":
                    response.sendRedirect("/customer/dashboard");
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
}

