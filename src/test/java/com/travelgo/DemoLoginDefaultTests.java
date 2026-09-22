package com.travelgo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The demo profile alone retains the safe false default from application-demo.properties. */
@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:travelgo-demo-default-guard;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "travelgo.demo.enabled=false"})
@ActiveProfiles(value = {"test", "demo"}, inheritProfiles = false)
class DemoLoginDefaultTests extends DemoLoginDisabledTests { }
