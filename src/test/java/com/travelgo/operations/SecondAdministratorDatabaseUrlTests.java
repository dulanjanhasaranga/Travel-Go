package com.travelgo.operations;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** No database connection or credentials: the offline entry point must validate before connecting. */
class SecondAdministratorDatabaseUrlTests {
    @Test void localOperatorConnectionsAndIdentityVerifiedRemoteConnectionsRemainAvailable() {
        for (String url : List.of(
                "jdbc:mysql://127.0.0.1:33317/travelgo_fixture",
                "jdbc:mysql://localhost/travelgo?sslMode=DISABLED&allowLoadLocalInfile=false",
                "jdbc:mysql://[::1]/travelgo?allowLoadLocalInfile=no",
                "jdbc:mysql://database.example.invalid:3306/travelgo?sslMode=VERIFY_IDENTITY&allowLoadLocalInfile=false",
                "jdbc:mysql://database.example.invalid/travelgo?sslMode=verify_identity"))
            assertDoesNotThrow(() -> SecondAdministratorProvisioner.validateDatabaseUrl(url));
    }

    @Test void remoteConnectionsCannotDisableOrDowngradeCertificateVerification() {
        for (String query : List.of("", "?sslMode=DISABLED", "?sslMode=PREFERRED", "?sslMode=REQUIRED", "?sslMode=VERIFY_CA"))
            assertThrows(IllegalArgumentException.class, () -> SecondAdministratorProvisioner.validateDatabaseUrl(
                    "jdbc:mysql://database.example.invalid/travelgo" + query));
    }

    @Test void embeddedCredentialsAndAmbiguousConnectionSettingsAreRejectedAndRedacted() {
        for (String url : List.of(
                "jdbc:mysql://operator:Synthetic-secret123!@127.0.0.1/travelgo",
                "jdbc:mysql://127.0.0.1/travelgo?password=Synthetic-secret123!",
                "jdbc:mysql://127.0.0.1/travelgo?%75ser=Synthetic-secret123!",
                "jdbc:mysql://127.0.0.1/travelgo?sslMode=DISABLED&SSLMODE=VERIFY_IDENTITY",
                "jdbc:mysql://127.0.0.1/travelgo?allowLoadLocalInfile=false&%61llowLoadLocalInfile=true",
                "jdbc:mysql://127.0.0.1:3306,other.example.invalid:3306/travelgo",
                "jdbc:mysql://127.0.0.1/travelgo/other",
                "jdbc:mysql://127.0.0.1/travelgo#Synthetic-secret123!",
                "jdbc:mysql://127.0.0.1/travelgo?sslMode=%broken",
                "jdbc:mysql://127.0.0.1/travelgo?=Synthetic-secret123!")) {
            var error = assertThrows(IllegalArgumentException.class, () -> SecondAdministratorProvisioner.validateDatabaseUrl(url));
            assertFalse(error.getMessage().contains("Synthetic-secret"));
            assertNull(error.getCause(), "Unsafe URI parsing diagnostics must never retain the supplied URL");
        }
    }

    @Test void localFileLoadingCannotBeEnabledEvenOnLoopback() {
        for (String value : List.of("true", "yes", "YES", "1", "", "unexpected"))
            assertThrows(IllegalArgumentException.class, () -> SecondAdministratorProvisioner.validateDatabaseUrl(
                    "jdbc:mysql://127.0.0.1/travelgo?allowLoadLocalInfile=" + value));
    }
}
