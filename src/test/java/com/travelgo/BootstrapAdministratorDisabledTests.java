package com.travelgo;

import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Verifies that a fresh installation never invents a predictable login account. */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:travelgo-bootstrap-disabled;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "travelgo.bootstrap.admin.email=",
        "travelgo.bootstrap.admin.password="
})
@ActiveProfiles(value = "bootstrap-safe", inheritProfiles = false)
class BootstrapAdministratorDisabledTests {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;

    @Test
    void freshStartupCreatesReferenceRolesButNoLoginAccountWithoutExplicitCredentials() {
        assertEquals(0, users.count());
        assertFalse(users.existsByEmailIgnoreCase("admin@travelgo.example.invalid"));
        assertTrueRoleExists("ADMIN");
        assertTrueRoleExists("CUSTOMER");
        assertTrueRoleExists("TRAVEL_CONSULTANT");
        assertTrueRoleExists("VISA_OFFICER");
    }

    private void assertTrueRoleExists(String name) {
        org.junit.jupiter.api.Assertions.assertTrue(roles.existsByRoleName(name));
    }
}
