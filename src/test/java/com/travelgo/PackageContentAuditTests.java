package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.AuditService;
import com.travelgo.service.TourPackageService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** Real H2 commit/rollback checks: a content change and its audit record are one transaction. */
@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:package-content-audit-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class PackageContentAuditTests {
    @Autowired TourPackageService catalogue;
    @Autowired TourPackageRepository packages;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired RoleRepository roles;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository auditLogs;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean AuditService audit;
    TransactionTemplate tx;
    Long packageId;
    String actor;

    @BeforeEach void fixture() {
        tx=new TransactionTemplate(transactions);
        actor="content-consultant-"+UUID.randomUUID()+"@example.invalid";
        packageId=tx.execute(status -> {
            Role role=roles.findByRoleName("TRAVEL_CONSULTANT").orElseGet(() -> {
                Role added=new Role(); added.setRoleName("TRAVEL_CONSULTANT"); return roles.save(added);
            });
            users.save(new User("Synthetic content consultant", actor, "unused-test-password", role));
            Destination destination=new Destination(); destination.setCity("Audit city"); destination.setCountry("Audit country"); destinations.save(destination);
            PackageCategory category=new PackageCategory(); category.setName("Audit category "+UUID.randomUUID()); categories.save(category);
            TourPackage tour=new TourPackage(); tour.setName("Synthetic audit tour"); tour.setDestination(destination); tour.setCategory(category);
            tour.setBasePrice(new BigDecimal("100.00")); tour.setDurationDays(4); tour.setMaxCapacity(10);
            tour.setItinerary("Original itinerary"); tour.setExcludedServices("Original exclusions"); tour.setTravelerInformation("Original audience"); tour.setActive(true);
            return packages.save(tour).getId();
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "unused",
                List.of(new SimpleGrantedAuthority("ROLE_TRAVEL_CONSULTANT"))));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private TourPackage stored() { return tx.execute(status -> packages.findById(packageId).orElseThrow()); }
    private List<AuditLog> events() {
        return tx.execute(status -> auditLogs.findAll().stream().filter(log -> "TourPackage".equals(log.getEntityName())
                && packageId.equals(log.getEntityId()) && "UPDATE_PACKAGE_CONTENT".equals(log.getAction())).toList());
    }

    @Test void successfulContentSaveCommitsNormalizedFieldsAndConsultantAuditWithoutTheContent() {
        catalogue.updateContent(packageId, "  Revised itinerary  ", " Revised exclusions ", " Revised audience ", false);
        TourPackage stored=stored();
        assertEquals("Revised itinerary", stored.getItinerary()); assertEquals("Revised exclusions", stored.getExcludedServices());
        assertEquals("Revised audience", stored.getTravelerInformation()); assertFalse(stored.isActive());
        assertEquals(1, events().size()); assertEquals(actor, events().get(0).getUsername());
        assertFalse(events().get(0).getDetails().contains("Revised itinerary"));
    }

    @Test void auditFailureRollsBackEveryContentAndAvailabilityChange() {
        doThrow(new IllegalStateException("Synthetic audit failure")).when(audit).logAction(
                eq("UPDATE_PACKAGE_CONTENT"), eq("TourPackage"), eq(packageId), anyString());
        assertThrows(IllegalStateException.class, () -> catalogue.updateContent(packageId, "Changed", "Changed", "Changed", false));
        TourPackage stored=stored();
        assertEquals("Original itinerary", stored.getItinerary()); assertEquals("Original exclusions", stored.getExcludedServices());
        assertEquals("Original audience", stored.getTravelerInformation()); assertTrue(stored.isActive()); assertTrue(events().isEmpty());
    }

    @Test void invalidContentCreatesNeitherAnAuditEventNorAChange() {
        assertThrows(IllegalArgumentException.class, () -> catalogue.updateContent(packageId, "x".repeat(10001), "Changed", "Changed", false));
        assertEquals("Original itinerary", stored().getItinerary()); assertTrue(stored().isActive()); assertTrue(events().isEmpty());
    }

    @Test void aCustomerCannotChangeContentOrCreateASuccessAudit() {
        String customerEmail="content-customer-"+UUID.randomUUID()+"@example.invalid";
        tx.executeWithoutResult(status -> {
            Role role=roles.findByRoleName("CUSTOMER").orElseGet(() -> {
                Role added=new Role(); added.setRoleName("CUSTOMER"); return roles.save(added);
            });
            users.save(new User("Synthetic customer", customerEmail, "unused-test-password", role));
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(customerEmail, "unused",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        assertThrows(AccessDeniedException.class, () -> catalogue.updateContent(packageId, "Changed", "Changed", "Changed", false));
        assertEquals("Original itinerary", stored().getItinerary()); assertTrue(events().isEmpty());
    }
}
