package com.travelgo;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/** Full synthetic recovery source. Excluded from the production JAR. */
public final class RecoveryAcceptanceFixture {
    private RecoveryAcceptanceFixture() {}

    public static void main(String[] args) {
        if (args.length != 1 || !args[0].matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("Supply a unique synthetic recovery run key.");
        }
        Path documents = Path.of("target", "operations-fixture", args[0], "source-documents").toAbsolutePath().normalize();
        System.setProperty("spring.devtools.restart.enabled", "false");
        SpringApplication app = new SpringApplication(TravelGoApplication.class, PlatformBrowserPreview.Fixtures.class);
        app.run("--spring.profiles.active=test,mysql-test", "--spring.config.import=",
                "--server.address=127.0.0.1", "--server.port=8081",
                "--server.servlet.session.cookie.name=TRAVELGO_RECOVERY_FIXTURE",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.datasource.username=root", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=create", "--spring.jpa.open-in-view=true",
                "--travelgo.upload-directory=" + documents,
                "--travelgo.payments.simulated=true", "--travelgo.stripe.enabled=false", "--travelgo.payhere.enabled=false",
                "--travelgo.bookings.pending-holds.enabled=false", "--travelgo.bookings.pending-holds.cleanup-enabled=false",
                "--travelgo.security.auth-abuse.store=memory", "--travelgo.security.auth-abuse.hmac-key=",
                "--travelgo.security.staff-mfa.enabled=false", "--travelgo.security.staff-mfa.encryption-key=",
                "--travelgo.mail.enabled=false", "--travelgo.mail.dispatch-enabled=false",
                "--travelgo.auth.google.enabled=false", "--travelgo.demo.enabled=false",
                "--travelgo.catalogue.install=false", "--travelgo.catalogue.restore-original=false",
                "--travelgo.bootstrap.admin.email=", "--travelgo.bootstrap.admin.password=");
        System.out.println("TRAVELGO_RECOVERY_FIXTURE_READY");
    }
}
