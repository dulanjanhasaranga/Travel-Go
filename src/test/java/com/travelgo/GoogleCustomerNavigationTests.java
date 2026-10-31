package com.travelgo;

import com.travelgo.dto.BookingRequest;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.BookingService;
import com.travelgo.service.CustomOAuth2UserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/session ownership with synthetic Google claims; no provider, SMTP or working database. */
@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class GoogleCustomerNavigationTests {
    enum Kind { OAUTH2, OIDC }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired GoogleAccountIdentityRepository identities;
    @Autowired PlatformTransactionManager manager;
    @Autowired Clock clock;
    @Autowired PasswordEncoder encoder;
    @Autowired BookingService bookings;
    @Autowired TourPackageRepository packages;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired DepartureRepository departures;
    @Autowired SystemSettingsRepository settings;
    MockMvc mvc;
    User customer;
    TourPackage tour;
    LocalDate travelDate;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.saveAndFlush(new Role("ADMIN", "Isolated reference")));
        Role role = roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.saveAndFlush(new Role("CUSTOMER", "Isolated customer")));
        customer = users.saveAndFlush(new User("Google customer", UUID.randomUUID() + "@example.invalid",
                encoder.encode("Local-google-version-123!"), role));
        var site = settings.findFirstByOrderByIdAsc().orElseGet(SystemSettings::new);
        site.setWebsiteStatus("ACTIVE"); settings.saveAndFlush(site);
        Destination destination = new Destination(); destination.setCountry("Test country"); destination.setCity("Test city");
        destinations.saveAndFlush(destination);
        PackageCategory category = new PackageCategory(); category.setName("Google test " + UUID.randomUUID()); categories.saveAndFlush(category);
        tour = new TourPackage(); tour.setName("Google customer test tour"); tour.setDestination(destination); tour.setCategory(category);
        tour.setBasePrice(new BigDecimal("100.00")); tour.setDurationDays(4); tour.setMaxCapacity(10); packages.saveAndFlush(tour);
        travelDate = LocalDate.now(clock).plusDays(30);
        Departure departure = new Departure(); departure.setTourPackage(tour); departure.setDepartureDate(travelDate);
        departure.setReturnDate(travelDate.plusDays(4)); departure.setTotalCapacity(10); departure.setAvailableSeats(10); departures.saveAndFlush(departure);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    OAuth2User principal(Kind kind) {
        String subject = "navigation-" + UUID.randomUUID();
        Map<String, Object> profile = Map.of("sub", subject, "email", customer.getEmail(), "name", customer.getName(),
                "email_verified", true, "hd", "example.invalid");
        var service = new CustomOAuth2UserService(users, roles, identities, clock, manager) {
            @Override protected OAuth2User fetchUser(OAuth2UserRequest request) {
                return new DefaultOAuth2User(List.of(), profile, "email");
            }
            @Override protected OidcUser fetchOidcUser(OidcUserRequest request) {
                return new DefaultOidcUser(List.of(), request.getIdToken(), new OidcUserInfo(profile), "email");
            }
        };
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("synthetic-client").clientSecret("synthetic-unused-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8081/login/oauth2/code/google").scope("openid", "email", "profile")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token").userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("email").build();
        Instant now = clock.instant();
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "synthetic-unused-token", now, now.plusSeconds(60));
        if (kind == Kind.OAUTH2) return service.loadUser(new OAuth2UserRequest(registration, access));
        Map<String, Object> claims = new java.util.HashMap<>(profile);
        claims.put("iss", "https://accounts.google.com"); claims.put("aud", List.of(registration.getClientId()));
        return service.loadOidcUser(new OidcUserRequest(registration, access,
                new OidcIdToken("synthetic-id-token", now, now.plusSeconds(60), claims)));
    }

    MockHttpSession signIn(Kind kind) throws Exception {
        OAuth2User principal = principal(kind);
        UserDetails local = assertInstanceOf(UserDetails.class, principal);
        assertEquals(customer.getEmail(), local.getUsername()); assertEquals(customer.getPassword(), local.getPassword());
        var restored = org.springframework.util.SerializationUtils.deserialize(org.springframework.util.SerializationUtils.serialize(principal));
        assertEquals(customer.getEmail(), assertInstanceOf(UserDetails.class, restored).getUsername());
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        var session = new MockHttpSession(context.getServletContext());
        var request = new MockHttpServletRequest(context.getServletContext()); request.setSession(session);
        var response = new MockHttpServletResponse();
        context.getBean("customSuccessHandler", AuthenticationSuccessHandler.class).onAuthenticationSuccess(request, response, authentication);
        assertEquals("/", response.getRedirectedUrl());
        assertEquals(customer.getPassword(), session.getAttribute("accountPasswordVersion"));
        var security = SecurityContextHolder.createEmptyContext(); security.setAuthentication(authentication);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        SecurityContextHolder.clearContext();
        return session;
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void googleSessionsCanOpenAndUpdateTheirCustomerJourney(Kind kind) throws Exception {
        MockHttpSession session = signIn(kind);
        var authentication = ((org.springframework.security.core.context.SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        Booking booking = bookings.createBooking(new BookingRequest(tour.getId(), travelDate, 1, null,
                List.of(new BookingRequest.TravelerInput("Google traveler", "TEST123", LocalDate.of(1990, 1, 1), "Other", "Test"))));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/packages/" + tour.getId() + "/book").session(session)).andExpect(status().isOk()).andExpect(view().name("customer/booking-form"));
        mvc.perform(get("/customer/dashboard").session(session)).andExpect(status().isOk()).andExpect(model().attribute("bookingCount", 1));
        mvc.perform(get("/customer/bookings").session(session)).andExpect(status().isOk()).andExpect(view().name("customer/bookings"));
        mvc.perform(get("/customer/bookings/" + booking.getId()).session(session)).andExpect(status().isOk()).andExpect(view().name("customer/booking-detail"));
        mvc.perform(get("/customer/visas").session(session)).andExpect(status().isOk()).andExpect(view().name("customer/visas"));
        mvc.perform(get("/customer/profile").session(session)).andExpect(status().isOk()).andExpect(view().name("customer/profile"));
        mvc.perform(get("/customer/payments/checkout/" + booking.getId()).session(session)).andExpect(status().isOk()).andExpect(view().name("customer/payment-checkout"));
        mvc.perform(get("/customer/bookings/" + booking.getId() + "/receipt").session(session)).andExpect(status().isConflict());
        mvc.perform(get("/customer/bookings/" + booking.getId() + "/invoice/" + Long.MAX_VALUE).session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/customer/profile").session(session).with(csrf()).param("name", "Updated Google customer")
                .param("phone", "12345").param("address", "Synthetic address"))
                .andExpect(redirectedUrl("/customer/profile"));
        assertEquals("Updated Google customer", users.findById(customer.getId()).orElseThrow().getName());
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void passwordChangeRevokesAnExistingGoogleSession(Kind kind) throws Exception {
        MockHttpSession session = signIn(kind);
        customer.setPassword(encoder.encode("Replacement-google-version-123!")); users.saveAndFlush(customer);
        mvc.perform(get("/customer/profile").session(session)).andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        assertTrue(session.isInvalid());
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void suspensionRevokesAnExistingGoogleSession(Kind kind) throws Exception {
        MockHttpSession session = signIn(kind);
        customer.setActive(false); users.saveAndFlush(customer);
        mvc.perform(get("/customer/profile").session(session)).andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        assertTrue(session.isInvalid());
    }
}
