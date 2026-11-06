package com.travelgo;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Re-runs the real workflow/admin rendering fixtures with the 44-table mapping. */
@TestPropertySource(properties="spring.jpa.mapping-resources=META-INF/cloud-evaluation-orm.xml")
class CloudSchemaCompatibleWorkflowTests extends AdminConsolePresentationTests {
    @Test void customerPackageAndStandaloneTransportMappingsRemainAvailable() throws Exception {
        var emf=context.getBean(jakarta.persistence.EntityManagerFactory.class);
        assertThrows(IllegalArgumentException.class,()->emf.getMetamodel().entity(com.travelgo.entity.TourPackage.class).getAttribute("transports"));
        assertNotNull(emf.getMetamodel().entity(com.travelgo.entity.Transport.class));
        assertTrue(packageRepo.findById(tour.getId()).isPresent());
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='tour_package_transports'",Integer.class));
    }
}
