package com.travelgo;

import java.util.Arrays;
import com.travelgo.config.CloudEvaluationSchemaApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication

public class TravelGoApplication {

	public static void main(String[] args) {
		boolean schemaInitializer = Arrays.asList(args).contains("--travelgo.cloud-evaluation.schema-init=CREATE_EMPTY_SCHEMA");
		if (schemaInitializer && !Arrays.asList(args).contains("--spring.profiles.active=cloud-evaluation-init")) {
			throw new IllegalStateException("Hosted schema initialization requires its reviewed cloud-evaluation-init profile argument.");
		}
		SpringApplication application = new SpringApplication(schemaInitializer
				? CloudEvaluationSchemaApplication.class : TravelGoApplication.class);
		if (schemaInitializer) application.setWebApplicationType(WebApplicationType.NONE);
		ConfigurableApplicationContext context = application.run(args);
		if (schemaInitializer) {
			System.out.println("=== Hosted schema initialization completed successfully ===");
			System.exit(SpringApplication.exit(context));
		}
	}

}


