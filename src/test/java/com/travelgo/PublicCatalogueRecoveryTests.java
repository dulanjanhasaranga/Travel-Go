package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.PublicCatalogueRecoveryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=", "travelgo.mail.enabled=false",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "spring.jpa.mapping-resources=META-INF/cloud-evaluation-orm.xml",
        "travelgo.cloud-evaluation.public-catalogue-recovery=local-public-catalogue-20261108"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PublicCatalogueRecoveryTests {
    @Autowired PublicCatalogueRecoveryService recovery;
    @Autowired SystemSettingsRepository settings;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired HotelRepository hotels;
    @Autowired DepartureRepository departures;
    @Autowired AuditLogRepository audit;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    Long privateUserId;

    @BeforeEach void fixture(){
        assertEquals(0,audit.count(),"The recovery runner must never execute in an ordinary test/local profile");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='TOUR_PACKAGE_TRANSPORTS'",Integer.class),
                "Recovery must work with the unchanged cloud schema and its accepted ORM compatibility mapping");
        SystemSettings agency=new SystemSettings();agency.setCompanyName("Synthetic recovery agency");settings.saveAndFlush(agency);
        Role role=roles.saveAndFlush(new Role("CUSTOMER","Synthetic private account"));
        User user=new User("Private fixture","private-recovery@example.invalid","unchanged-private-fixture-hash",role);
        privateUserId=users.saveAndFlush(user).getId();
    }

    @Test void restoresOnlyPublicCatalogueAndIsIdempotent(){
        var result=recovery.restoreOnce();assertTrue(result.applied());assertEquals(52,result.insertedRecords());
        assertEquals(9,destinations.count());assertEquals(3,categories.count());assertEquals(9,packages.count());
        assertEquals(4,hotels.count());assertEquals(27,departures.count());assertEquals(1,audit.count());
        assertTrue(packages.findAll().stream().allMatch(TourPackage::isActive));
        assertTrue(departures.findAll().stream().allMatch(d->d.getAvailableSeats().equals(d.getTotalCapacity())));
        assertFalse(recovery.restoreOnce().applied());assertEquals(9,packages.count());assertEquals(1,audit.count());
        assertPrivateDataPreserved();
    }

    @Test void keepsExistingEditsInactiveRecordsAndReservedSeats(){
        Destination paris=new Destination();paris.setCountry("France");paris.setCity("Paris");paris.setActive(false);paris=destinations.saveAndFlush(paris);
        PackageCategory category=new PackageCategory();category.setName("Cultural Heritage");category.setActive(false);category=categories.saveAndFlush(category);
        TourPackage tour=tour("European Classical Journey",paris,category);tour.setActive(false);tour.setBasePrice(new BigDecimal("9999.00"));tour.setDescription("Existing operator edit");tour=packages.saveAndFlush(tour);
        Departure departure=new Departure();departure.setTourPackage(tour);departure.setDepartureDate(LocalDate.of(2026,10,20));
        departure.setReturnDate(LocalDate.of(2026,10,27));departure.setTotalCapacity(99);departure.setAvailableSeats(7);departure=departures.saveAndFlush(departure);
        recovery.restoreOnce();
        assertFalse(recovery.repairImportedParisVisibility(),"An import reusing existing edits must not change inactive records");
        TourPackage existing=packages.findById(tour.getId()).orElseThrow();assertFalse(existing.isActive());
        assertEquals(0,new BigDecimal("9999.00").compareTo(existing.getBasePrice()));assertEquals("Existing operator edit",existing.getDescription());
        assertFalse(destinations.findById(paris.getId()).orElseThrow().isActive());
        assertFalse(categories.findById(category.getId()).orElseThrow().isActive());
        assertEquals(7,departures.findById(departure.getId()).orElseThrow().getAvailableSeats());
        assertPrivateDataPreserved();
    }

    @Test void repairsOnlyTheKnownImportedTestCitySoAllNineToursAreVisible(){
        recovery.restoreOnce();assertTrue(recovery.repairImportedParisVisibility());
        Destination paris=destinations.findAll().stream().filter(d->"Paris".equals(d.getCity())).findFirst().orElseThrow();
        assertTrue(paris.isActive());assertTrue(destinations.findAll().stream().noneMatch(d->"Parissss".equals(d.getCity())));
        assertEquals(9,packages.findAll().stream().filter(p->p.isActive()&&destinations.findById(p.getDestination().getId()).orElseThrow().isActive()).count());
        assertFalse(recovery.repairImportedParisVisibility());assertEquals(2,audit.count());assertPrivateDataPreserved();
    }

    @Test void anyMidImportAmbiguityRollsBackAllNewRowsAndCompletionMarker(){
        Destination destination=new Destination();destination.setCountry("Synthetic");destination.setCity("Existing");destination=destinations.saveAndFlush(destination);
        PackageCategory category=new PackageCategory();category.setName("Existing custom category");category=categories.saveAndFlush(category);
        packages.saveAndFlush(tour("Existing duplicate",destination,category));packages.saveAndFlush(tour("EXISTING DUPLICATE",destination,category));
        assertThrows(IllegalStateException.class,()->recovery.restoreOnce());
        assertEquals(1,destinations.count());assertEquals(1,categories.count());assertEquals(2,packages.count());
        assertEquals(0,hotels.count());assertEquals(0,departures.count());assertEquals(0,audit.count());assertPrivateDataPreserved();
    }

    @Test void overlappingStartsImportExactlyOnce() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Callable<PublicCatalogueRecoveryService.Result> task=()->{start.await();return recovery.restoreOnce();};
            Future<PublicCatalogueRecoveryService.Result> first=pool.submit(task),second=pool.submit(task);start.countDown();
            var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertNotEquals(a.applied(),b.applied());assertEquals(52,a.insertedRecords()+b.insertedRecords());
            assertEquals(9,packages.count());assertEquals(27,departures.count());assertEquals(1,audit.count());
        }finally{pool.shutdownNow();}
        assertPrivateDataPreserved();
    }

    private TourPackage tour(String name,Destination destination,PackageCategory category){
        TourPackage value=new TourPackage();value.setName(name);value.setDestination(destination);value.setCategory(category);
        value.setBasePrice(new BigDecimal("500.00"));value.setDurationDays(7);value.setMaxCapacity(12);return value;
    }
    private void assertPrivateDataPreserved(){
        assertEquals(1,users.count());assertEquals("unchanged-private-fixture-hash",users.findById(privateUserId).orElseThrow().getPassword());
        assertEquals(0,bookings.count());assertEquals(0,payments.count());
    }
}
