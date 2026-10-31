package com.travelgo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Disposable browser journey harness. Test-only; never included in the application JAR. */
public class JourneyBrowserPreview {
    static final Path CLOCK_FILE = Path.of("target/journey-preview-clock.txt").toAbsolutePath();
    static final Instant INITIAL_TIME = Instant.parse("2030-01-01T12:00:00Z");

    public static void main(String[] args) throws Exception {
        System.setProperty("spring.devtools.restart.enabled", "false");
        Files.createDirectories(CLOCK_FILE.getParent());
        Files.writeString(CLOCK_FILE, INITIAL_TIME.toString());
        new SpringApplication(TravelGoApplication.class, PlatformBrowserPreview.Fixtures.class,
                JourneyTime.class).run(
                "--spring.profiles.active=test", "--spring.config.import=",
                "--server.address=127.0.0.1", "--server.port=8082",
                "--server.servlet.session.cookie.name=TRAVELGO_JOURNEY_PREVIEW",
                "--spring.datasource.url=jdbc:h2:mem:travelgo-journey-preview;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.jpa.open-in-view=true",
                "--travelgo.mail.enabled=false", "--travelgo.mail.dispatch-enabled=false",
                "--travelgo.payhere.enabled=false", "--travelgo.stripe.enabled=false",
                "--travelgo.payments.simulated=true", "--travelgo.bookings.require-supplier-confirmation=true",
                "--travelgo.upload-directory=target/journey-preview-uploads", "--spring.thymeleaf.cache=false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JourneyTime {
        @Bean @Primary Clock journeyClock() { return new PreviewClock(ZoneOffset.UTC); }
    }

    /** A file changes only this isolated preview's clock; there is no production clock endpoint. */
    static final class PreviewClock extends Clock {
        private final ZoneId zone;
        private volatile Instant lastValid = INITIAL_TIME;
        PreviewClock(ZoneId zone) { this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new PreviewClock(zone); }
        @Override public Instant instant() {
            try { lastValid = Instant.parse(Files.readString(CLOCK_FILE).trim()); }
            catch (java.io.IOException | java.time.format.DateTimeParseException transientWrite) {
                // A file replacement cannot briefly invalidate requests already being served.
            }
            return lastValid;
        }
    }
}
