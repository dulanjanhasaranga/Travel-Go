package com.travelgo.config;

import com.travelgo.service.DemoAccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component // Re-enabled to generate demo accounts
@Profile("demo & !test & !mysql-test & !prod & !production")
@ConditionalOnProperty(name = "travelgo.demo.enabled", havingValue = "true")
public class DemoAccountInitializer implements CommandLineRunner {
    private final DemoAccountService accounts;
    public DemoAccountInitializer(DemoAccountService accounts) { this.accounts = accounts; }
    @Override public void run(String... args) { accounts.provision(); }
}
