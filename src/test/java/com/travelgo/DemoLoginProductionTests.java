package com.travelgo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Runs the disabled-mode contract even when both shortcut switches are enabled. */
@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:travelgo-demo-production-guard;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "travelgo.demo.enabled=true"})
@ActiveProfiles(value = {"test", "demo", "production"}, inheritProfiles = false)
class DemoLoginProductionTests extends DemoLoginDisabledTests { }
