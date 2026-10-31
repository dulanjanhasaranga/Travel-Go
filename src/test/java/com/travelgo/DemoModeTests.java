package com.travelgo;

import com.travelgo.config.DemoMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class DemoModeTests {
    @Test void bothProfileAndFlagAreRequiredAndProductionProfilesAlwaysDisable() {
        MockEnvironment env = new MockEnvironment();
        DemoMode mode = new DemoMode(env);
        assertFalse(mode.isEnabled());
        env.setActiveProfiles("demo");
        assertFalse(mode.isEnabled());
        env.setActiveProfiles("mysql");
        env.setProperty("travelgo.demo.enabled", "true");
        assertFalse(mode.isEnabled());
        env.setActiveProfiles("mysql", "demo");
        assertTrue(mode.isEnabled());
        env.setActiveProfiles("demo", "production");
        assertFalse(mode.isEnabled());
        env.setActiveProfiles("demo", "prod");
        assertFalse(mode.isEnabled());
        env.setActiveProfiles("demo");
        env.setProperty("travelgo.demo.enabled", "false");
        assertFalse(mode.isEnabled());
    }
}
