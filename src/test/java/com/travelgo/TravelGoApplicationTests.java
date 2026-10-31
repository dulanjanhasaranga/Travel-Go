package com.travelgo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties="spring.config.import=")
class TravelGoApplicationTests {

	@Test
	void contextLoads() {
	}

}
