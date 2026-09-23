package com.mystaria.phantasmon_backend;

import org.springframework.boot.SpringApplication;

public class TestPhantasmonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(PhantasmonBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
