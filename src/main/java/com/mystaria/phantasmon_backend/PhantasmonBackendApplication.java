package com.mystaria.phantasmon_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// No in-memory user: authentication is JWT only, and the auto-configured one logged a generated password at every start.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class PhantasmonBackendApplication {

	/** Kept for the admin reboot (BackendRestarter), which starts the application again in the same process. */
	private static String[] arguments = new String[0];

	public static void main(String[] args) {
		arguments = args.clone();
		SpringApplication.run(PhantasmonBackendApplication.class, args);
	}

	public static String[] arguments() {
		return arguments.clone();
	}

}
