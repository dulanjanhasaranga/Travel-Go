package com.travelgo.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Both an explicit demo profile and an explicit flag are required. */
@Component
public class DemoMode {
    private final Environment environment;

    public DemoMode(Environment environment) {
        this.environment = environment;
    }

    public boolean isEnabled() {
        return environment.acceptsProfiles(Profiles.of("demo & !prod & !production"))
                && environment.getProperty("travelgo.demo.enabled", Boolean.class, false);
    }
}
