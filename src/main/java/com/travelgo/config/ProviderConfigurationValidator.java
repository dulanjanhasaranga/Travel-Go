package com.travelgo.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/** Fail unsafe opt-in provider configuration without network requests or secret logging. */
@Component
public class ProviderConfigurationValidator {
    private final Environment environment;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    public ProviderConfigurationValidator(Environment environment, ObjectProvider<ClientRegistrationRepository> registrations) {
        this.environment = environment; this.registrations = registrations;
    }
    @PostConstruct public void validate() {
        boolean mailEnabled = flag("travelgo.mail.enabled");
        boolean dispatch = flag("travelgo.mail.dispatch-enabled");
        if (dispatch && !mailEnabled) throw new IllegalStateException("Transactional email dispatch requires mail delivery to be enabled.");
        String transport = environment.getProperty("travelgo.mail.transport", "smtp");
        if (!java.util.Set.of("smtp", "brevo-api").contains(transport))
            throw new IllegalStateException("Choose a supported transactional email transport.");
        if (mailEnabled) {
            String from = environment.getProperty("travelgo.mail.from", "");
            if (!from.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || from.endsWith(".example"))
                throw new IllegalStateException("Configure an approved sender address before enabling mail.");
            if ("brevo-api".equals(transport)) {
                if (!com.travelgo.service.BrevoTransactionalMailSender.isConfigurationValid(
                        environment.getProperty("travelgo.mail.brevo-api-key", ""),
                        environment.getProperty("travelgo.public-base-url", "")))
                    throw new IllegalStateException("Brevo HTTPS delivery requires a private API key and a public HTTPS website origin.");
            } else {
                String host = environment.getProperty("spring.mail.host", "");
                int port = environment.getProperty("spring.mail.port", Integer.class, 587);
                if (host.isBlank() || host.contains("/") || host.contains("@") || port < 1 || port > 65535)
                    throw new IllegalStateException("Configure a valid SMTP host and port before enabling mail.");
                if (!loopback(host) && !flag("spring.mail.properties.mail.smtp.ssl.enable")
                        && !(flag("spring.mail.properties.mail.smtp.starttls.enable") && flag("spring.mail.properties.mail.smtp.starttls.required")))
                    throw new IllegalStateException("Remote SMTP must require STARTTLS or use provider-approved implicit TLS.");
                if (!loopback(host) && !flag("spring.mail.properties.mail.smtp.ssl.checkserveridentity"))
                    throw new IllegalStateException("Remote SMTP must verify the provider's TLS certificate identity.");
            }
            publicOrigin();
        }
        if (flag("travelgo.payhere.enabled")) {
            if (flag("travelgo.payments.simulated") || flag("travelgo.stripe.enabled"))
                throw new IllegalStateException("PayHere requires simulation and Stripe checkout to be disabled.");
            String mode = environment.getProperty("travelgo.payhere.mode", "test");
            if (!"test".equals(mode) && !"live".equals(mode))
                throw new IllegalStateException("Select the PayHere test or live environment explicitly.");
            if (!flag("travelgo.payhere.usd-approved"))
                throw new IllegalStateException("Confirm PayHere merchant support for the current USD catalogue before enabling checkout.");
            if (!environment.getProperty("travelgo.payhere.merchant-id", "").matches("^[0-9]{5,20}$"))
                throw new IllegalStateException("Configure the PayHere merchant ID before enabling checkout.");
            for (String setting : new String[]{"travelgo.payhere.merchant-secret", "travelgo.payhere.app-id", "travelgo.payhere.app-secret"}) {
                String value = environment.getProperty(setting, "");
                if (value.length() < 16 || value.length() > 500 || value.chars().anyMatch(Character::isWhitespace)
                        || value.toLowerCase(java.util.Locale.ROOT).contains("placeholder")
                        || value.toLowerCase(java.util.Locale.ROOT).contains("replace_"))
                    throw new IllegalStateException("Configure approved PayHere merchant and retrieval credentials before enabling checkout.");
            }
            publicOrigin();
            if ("live".equals(mode) && !"https".equals(URI.create(environment.getProperty("travelgo.public-base-url", "http://localhost:8081")).getScheme()))
                throw new IllegalStateException("Live PayHere requires an HTTPS public origin.");
        }
        if (flag("travelgo.auth.google.enabled")) {
            var repository = registrations.getIfAvailable();
            var google = repository == null ? null : repository.findByRegistrationId("google");
            if (google == null || google.getClientId() == null || !google.getClientId().endsWith(".apps.googleusercontent.com")
                    || google.getClientSecret() == null || google.getClientSecret().isBlank()
                    || google.getClientSecret().contains("placeholder") || !google.getScopes().contains("openid"))
                throw new IllegalStateException("Google sign-in requires the oauth profile, approved Google credentials and OpenID Connect.");
            publicOrigin();
        }
    }
    private boolean flag(String name) { return environment.getProperty(name, Boolean.class, false); }
    private void publicOrigin() {
        try {
            URI uri = URI.create(environment.getProperty("travelgo.public-base-url", "http://localhost:8081"));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback(uri.getHost()))))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("External services require an HTTPS public origin; local tests may use loopback HTTP.");
        }
    }
    private boolean loopback(String host) { return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host); }
}
