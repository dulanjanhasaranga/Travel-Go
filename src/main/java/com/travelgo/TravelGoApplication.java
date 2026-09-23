package com.travelgo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.cache.annotation.EnableCaching
public class TravelGoApplication {

	public static void main(String[] args) {
		SpringApplication.run(TravelGoApplication.class, args);
	}

}

