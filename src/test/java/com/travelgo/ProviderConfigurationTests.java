package com.travelgo;
import com.travelgo.config.ProviderConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderConfigurationTests {
    final ObjectProvider<ClientRegistrationRepository> registrations = mock(ObjectProvider.class);
    void validate(MockEnvironment environment) { new ProviderConfigurationValidator(environment, registrations).validate(); }
    MockEnvironment mail() { return new MockEnvironment().withProperty("travelgo.mail.enabled", "true")
            .withProperty("spring.mail.host", "smtp.provider.test").withProperty("spring.mail.port", "587")
            .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true")
            .withProperty("travelgo.mail.from", "support@travelgo.test").withProperty("travelgo.public-base-url", "https://travelgo.test"); }
    @Test void disabledProvidersNeedNoSecretsOrExternalCalls() { assertDoesNotThrow(() -> validate(new MockEnvironment())); verifyNoInteractions(registrations); }
    @Test void dispatcherCannotStartWithoutAnEnabledTransport() { assertThrows(IllegalStateException.class, () -> validate(new MockEnvironment().withProperty("travelgo.mail.dispatch-enabled", "true"))); }
    @Test void remoteMailRequiresTlsRatherThanOpportunisticDowngrade() {
        assertThrows(IllegalStateException.class, () -> validate(mail().withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")));
        assertDoesNotThrow(() -> validate(mail().withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")));
        assertDoesNotThrow(() -> validate(mail().withProperty("spring.mail.properties.mail.smtp.ssl.enable", "true")));
    }
    @Test void loopbackCaptureMayRunWithoutTlsInIsolatedTests() { assertDoesNotThrow(() -> validate(mail().withProperty("spring.mail.host", "127.0.0.1"))); }
    @Test void encryptedRemoteMailMustAlsoVerifyCertificateIdentity() {
        assertThrows(IllegalStateException.class, () -> validate(mail().withProperty("spring.mail.properties.mail.smtp.ssl.enable", "true")
                .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "false")));
        assertThrows(IllegalStateException.class, () -> validate(mail().withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
                .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "false")));
        assertDoesNotThrow(() -> validate(mail().withProperty("spring.mail.host", "127.0.0.1")
                .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "false")));
    }
    MockEnvironment brevoApi() { return new MockEnvironment()
            .withProperty("travelgo.mail.enabled", "true").withProperty("travelgo.mail.transport", "brevo-api")
            .withProperty("travelgo.mail.brevo-api-key", "synthetic_api_key_012345678901234567890123456789")
            .withProperty("travelgo.mail.from", "support@travelgo.test")
            .withProperty("travelgo.public-base-url", "https://travelgo.test"); }
    @Test void explicitHttpsTransportNeedsNoSmtpHostOrPassword() {
        assertDoesNotThrow(() -> validate(brevoApi()));
        verifyNoInteractions(registrations);
    }
    @Test void httpsTransportRequiresApiKeyAndPublicHttpsOriginWithoutLeakingSettings() {
        String privateValue="placeholder-private-api-key-that-must-not-be-echoed";
        var failure=assertThrows(IllegalStateException.class, () -> validate(brevoApi()
                .withProperty("travelgo.mail.brevo-api-key", privateValue)));
        assertFalse(failure.getMessage().contains(privateValue));
        assertThrows(IllegalStateException.class, () -> validate(brevoApi().withProperty("travelgo.mail.brevo-api-key", "")));
        for (String origin : java.util.List.of("http://travelgo.test", "https://localhost", "https://travelgo.test/path", "https://user:secret@travelgo.test"))
            assertThrows(IllegalStateException.class, () -> validate(brevoApi().withProperty("travelgo.public-base-url", origin)));
    }
    @Test void unknownTransportCannotSilentlySelectSmtp() {
        assertThrows(IllegalStateException.class, () -> validate(brevoApi().withProperty("travelgo.mail.transport", "brevo")));
        assertThrows(IllegalStateException.class, () -> validate(new MockEnvironment().withProperty("travelgo.mail.transport", "typo")));
    }
    @Test void invalidSenderAndEmbeddedCredentialOriginAreRejected() {
        assertThrows(IllegalStateException.class, () -> validate(mail().withProperty("travelgo.mail.from", "no-reply@travelgo.example")));
        assertThrows(IllegalStateException.class, () -> validate(mail().withProperty("spring.mail.host", "127.0.0.1")
                .withProperty("travelgo.public-base-url", "https://username:password@travelgo.test")));
    }
    @Test void enabledGoogleRequiresActualOidcRegistration() {
        assertThrows(IllegalStateException.class, () -> validate(new MockEnvironment().withProperty("travelgo.auth.google.enabled", "true")));
        verify(registrations).getIfAvailable();
    }
    MockEnvironment payhere() { return new MockEnvironment().withProperty("travelgo.payhere.enabled", "true")
            .withProperty("travelgo.payments.simulated", "false").withProperty("travelgo.payhere.mode", "test")
            .withProperty("travelgo.payhere.usd-approved", "true").withProperty("travelgo.payhere.merchant-id", "1234567")
            .withProperty("travelgo.payhere.merchant-secret", "synthetic-merchant-secret")
            .withProperty("travelgo.payhere.app-id", "synthetic-retrieval-app-id")
            .withProperty("travelgo.payhere.app-secret", "synthetic-retrieval-app-secret")
            .withProperty("travelgo.public-base-url", "https://travelgo.test"); }
    @Test void payhereRequiresAnExclusiveRealProviderSelectionAndCurrencyApproval() {
        assertDoesNotThrow(() -> validate(payhere()));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.payments.simulated", "true")));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.stripe.enabled", "true")));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.payhere.usd-approved", "false")));
    }
    @Test void payhereRejectsMissingRetrievalCredentialsAndUnsafeMerchantIdentifiersWithoutEchoingValues() {
        for (String setting : new String[]{"travelgo.payhere.merchant-secret", "travelgo.payhere.app-id", "travelgo.payhere.app-secret"}) {
            assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty(setting, "")));
        }
        String unsafe = "1234567?secret=synthetic-private-value";
        var failure = assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.payhere.merchant-id", unsafe)));
        assertFalse(failure.getMessage().contains(unsafe));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.payhere.app-secret", "REPLACE_IN_SECRET_MANAGER")));
    }
    @Test void payhereTestAndLiveOriginsAreValidatedIndependently() {
        assertDoesNotThrow(() -> validate(payhere().withProperty("travelgo.public-base-url", "http://127.0.0.1:8080")));
        assertDoesNotThrow(() -> validate(payhere().withProperty("travelgo.payhere.mode", "live")));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.payhere.mode", "live")
                .withProperty("travelgo.public-base-url", "http://127.0.0.1:8080")));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.payhere.mode", "sandbox")));
        assertThrows(IllegalStateException.class, () -> validate(payhere().withProperty("travelgo.public-base-url", "https://user:password@travelgo.test")));
    }
}
