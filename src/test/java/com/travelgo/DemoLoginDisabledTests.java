package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:travelgo-demo-disabled;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "travelgo.demo.enabled=true"})
@ActiveProfiles("test")
class DemoLoginDisabledTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired CustomUserDetailsService details;
    @Autowired PasswordEncoder passwords;

    @Test void disabledModeCannotShowOrUseShortcutOrRetainDemoSession() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Quick Demo Access"))));
        mvc.perform(post("/auth/demo-login").with(csrf()).param("account", "admin")).andExpect(status().isNotFound());
        assertEquals(0, users.count()); // No application initializer runs in isolated tests.
        Role role = roles.save(new Role("ADMIN", "Isolated test role"));
        User demo = new User("DEMO Test", "demo-test@example.invalid", passwords.encode("Isolated-demo-test!"), role);
        demo.setDemoAccount(true); users.save(demo);
        var principal = details.loadUserByUsername(demo.getEmail());
        assertFalse(principal.isEnabled());
        mvc.perform(post("/auth/login").with(csrf()).param("email", demo.getEmail()).param("password", "Isolated-demo-test!"))
                .andExpect(redirectedUrl("/auth/login?error=true"));
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", security);
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        assertTrue(session.isInvalid());
    }
}
