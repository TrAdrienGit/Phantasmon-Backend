package com.mystaria.phantasmon_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// No in-memory user: authentication is JWT only, and the auto-configured one logged a generated password at every start.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class PhantasmonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PhantasmonBackendApplication.class, args);
	}

}
