package com.travelgo;

import com.travelgo.entity.User;
import com.travelgo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the explicit first-admin path in a disposable H2 database. */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:travelgo-bootstrap-configured;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "travelgo.bootstrap.admin.email=First.Admin@travelgo.example.invalid",
        "travelgo.bootstrap.admin.password=Bootstrap-Only-Test-Password-42!",
        "travelgo.bootstrap.admin.name=First TravelGO Administrator"
})
@ActiveProfiles(value = "bootstrap-configured", inheritProfiles = false)
class BootstrapAdministratorConfiguredTests {
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void explicitCredentialsCreateExactlyOneActiveNonDemoAdministrator() {
        assertEquals(1, users.count());
        User user = users.findByEmailIgnoreCase("first.admin@travelgo.example.invalid").orElseThrow();
        assertEquals("First TravelGO Administrator", user.getName());
        assertEquals("ADMIN", user.getRole().getRoleName());
        assertTrue(user.isActive());
        assertFalse(user.isDemoAccount());
        assertTrue(passwords.matches("Bootstrap-Only-Test-Password-42!", user.getPassword()));
    }
}
