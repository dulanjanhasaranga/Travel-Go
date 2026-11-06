package com.travelgo;

import jakarta.persistence.Entity;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.hibernate.boot.*;
import org.hibernate.boot.registry.*;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable in-memory databases only. No Spring application or provider is started. */
class CloudSchemaCompatibilityTests {
    StandardServiceRegistry registry(String url,String action) {
        assertTrue(url.startsWith("jdbc:h2:mem:cloud_compat_"));
        return new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url",url)
            .applySetting("hibernate.connection.username","sa")
            .applySetting("hibernate.connection.password","")
            .applySetting("hibernate.hbm2ddl.auto",action)
            .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .build();
    }
    Metadata metadata(StandardServiceRegistry registry,boolean compatible) throws Exception {
        var sources=new MetadataSources(registry);
        try(var files=Files.list(Path.of("target/classes/com/travelgo/entity"))) {
            for(Path file:files.filter(p->p.toString().endsWith(".class")&&!p.getFileName().toString().contains("$")).toList()) {
                Class<?> type=Class.forName("com.travelgo.entity."+file.getFileName().toString().replace(".class",""));
                if(type.isAnnotationPresent(Entity.class)) sources.addAnnotatedClass(type);
            }
        }
        if(compatible) sources.addResource("META-INF/cloud-evaluation-orm.xml");
        return sources.buildMetadata();
    }
    Set<String> tables(Metadata metadata) {
        Set<String> names=new TreeSet<>();
        for(var namespace:metadata.getDatabase().getNamespaces())for(var table:namespace.getTables())if(table.isPhysicalTable())names.add(table.getName());
        return names;
    }
    void validate(String url,boolean compatible) throws Exception {
        var registry=registry(url,"validate");
        try(var factory=metadata(registry,compatible).buildSessionFactory()) {}
        finally {StandardServiceRegistryBuilder.destroy(registry);}
    }
    @Test void onlyUnusedAssociationDiffersAndExistingSchemaPassesFullValidation() throws Exception {
        String url="jdbc:h2:mem:cloud_compat_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var registry=registry(url,"create");
        try {
            Metadata full=metadata(registry,false), compatible=metadata(registry,true);
            Set<String> fullTables=tables(full),cloudTables=tables(compatible);
            assertEquals(45,fullTables.size());assertEquals(44,cloudTables.size());
            fullTables.removeAll(cloudTables);assertEquals(Set.of("tour_package_transports"),fullTables);
            try(var factory=compatible.buildSessionFactory()) {
                assertThrows(IllegalArgumentException.class,()->factory.getMetamodel().entity(com.travelgo.entity.TourPackage.class).getAttribute("transports"));
                assertNotNull(factory.getMetamodel().entity(com.travelgo.entity.Transport.class));
            }
        } finally {StandardServiceRegistryBuilder.destroy(registry);}
        assertDoesNotThrow(()->validate(url,true));
        var originalFailure=assertThrows(SchemaManagementException.class,()->validate(url,false));
        assertTrue(originalFailure.getMessage().contains("tour_package_transports"));
        try(var connection=DriverManager.getConnection(url,"sa","");var query=connection.createStatement()) {
            query.executeUpdate("DROP TABLE authentication_rate_cells");
        }
        var stillValidated=assertThrows(SchemaManagementException.class,()->validate(url,true));
        assertTrue(stillValidated.getMessage().contains("authentication_rate_cells"));
    }
    @Test void overrideIsConfinedToHostedProfile() throws Exception {
        String override="spring.jpa.mapping-resources=META-INF/cloud-evaluation-orm.xml";
        assertTrue(Files.readString(Path.of("src/main/resources/application-cloud-evaluation.properties")).contains(override));
        try(var files=Files.list(Path.of("src/main/resources"))) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("application(?:-[^.]+)?\\.properties")&&!p.getFileName().toString().equals("application-cloud-evaluation.properties")).toList()) {
                assertFalse(Files.readString(file).contains(override),file.toString());
            }
        }
    }
    @Test void noImplementedApplicationFlowDependsOnTheUnusedAssociation() throws Exception {
        try(var files=Files.walk(Path.of("src/main"))) {
            for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().matches(".*\\.(java|html|js)$")).toList()) {
                if(file.getFileName().toString().equals("TourPackage.java"))continue;
                String text=Files.readString(file);
                assertFalse(text.contains("getTransports(")||text.contains("setTransports(")||java.util.regex.Pattern.compile("\\b\\w+\\.transports\\b").matcher(text).find(),file.toString());
            }
        }
    }
}
