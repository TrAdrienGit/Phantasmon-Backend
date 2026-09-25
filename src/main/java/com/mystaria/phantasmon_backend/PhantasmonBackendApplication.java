package com.mystaria.phantasmon_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PhantasmonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PhantasmonBackendApplication.class, args);
	}

}
