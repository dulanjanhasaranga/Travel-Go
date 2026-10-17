package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.EmailVerificationTokenRepository;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=false",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class RegistrationDiscoveryTests {
    @Autowired WebApplicationContext context;
    @Autowired RoleRepository roles;
    @Autowired SystemSettingsRepository settings;
    @Autowired UserRepository users;
    @Autowired EmailVerificationTokenRepository tokens;
    @Autowired OutboundEmailRepository outbox;
    @Autowired UserService accounts;
    @MockitoBean JavaMailSender sender;
    MockMvc mvc;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.saveAndFlush(new Role("ADMIN", "Isolated signup test")));
        roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.saveAndFlush(new Role("CUSTOMER", "Isolated signup test")));
        SystemSettings site = settings.findFirstByOrderByIdAsc().orElseGet(SystemSettings::new);
        site.setWebsiteStatus("ACTIVE");
        settings.saveAndFlush(site);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        assertFalse(accounts.isEmailVerificationConfigured());
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"/", "/packages", "/auth/login"})
    void anonymousPagesKeepAnAccountCreationLinkVisibleWithoutSmtp(String path) throws Exception {
        var html = mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/auth/register\"")))
                .andExpect(content().string(anyOf(containsString("Create account"), containsString("Create an account"))))
                .andReturn().getResponse().getContentAsString();
        if (!path.startsWith("/auth/")) {
            int footer = html.indexOf("<footer");
            assertTrue(footer >= 0, "Public pages must retain their footer");
            assertTrue(html.substring(footer).contains("href=\"/auth/register\""),
                    "The footer must retain account creation access when SMTP is unavailable");
        }
    }

    @Test void accountCreationPageExplainsAvailabilityAndOffersContactHelp() throws Exception {
        mvc.perform(get("/auth/register")).andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("registrationAvailable", false))
                .andExpect(content().string(containsString("Create an account")))
                .andExpect(content().string(containsString("Contact our team")))
                .andExpect(content().string(containsString("href=\"/contact\"")))
                .andExpect(content().string(not(containsString("action=\"/auth/register\""))));
    }

    @Test void unavailableRegistrationCreatesNoAccountTokenOrEmail() throws Exception {
        long userCount = users.count(), tokenCount = tokens.count(), emailCount = outbox.count();
        String address = UUID.randomUUID() + "@example.invalid";
        mvc.perform(post("/auth/register").with(csrf()).param("name", "Signup test")
                        .param("email", address).param("password", "Registration-only-123!")
                        .param("confirmPassword", "Registration-only-123!"))
                .andExpect(status().isOk()).andExpect(view().name("auth/register"))
                .andExpect(model().attribute("errorMessage", containsString("Registration is temporarily unavailable")));
        assertTrue(users.findByEmailIgnoreCase(address).isEmpty());
        assertEquals(userCount, users.count());
        assertEquals(tokenCount, tokens.count());
        assertEquals(emailCount, outbox.count());
        verifyNoInteractions(sender);
    }

    @Test void accountCreationStillRejectsRequestsWithoutCsrf() throws Exception {
        long userCount = users.count(), tokenCount = tokens.count(), emailCount = outbox.count();
        mvc.perform(post("/auth/register").param("name", "Signup test")
                        .param("email", UUID.randomUUID() + "@example.invalid")
                        .param("password", "Registration-only-123!")
                        .param("confirmPassword", "Registration-only-123!"))
                .andExpect(status().isForbidden());
        assertEquals(userCount, users.count());
        assertEquals(tokenCount, tokens.count());
        assertEquals(emailCount, outbox.count());
        verifyNoInteractions(sender);
    }
}
