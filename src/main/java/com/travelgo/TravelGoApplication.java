package com.travelgo;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication

public class TravelGoApplication {

	public static void main(String[] args) {
		boolean schemaInitializer = Arrays.asList(args).contains("--travelgo.cloud-evaluation.schema-init=CREATE_EMPTY_SCHEMA");
		SpringApplication application = new SpringApplication(TravelGoApplication.class);
		if (schemaInitializer) application.setWebApplicationType(WebApplicationType.NONE);
		ConfigurableApplicationContext context = application.run(args);
		if (schemaInitializer) System.exit(SpringApplication.exit(context));
	}

}


