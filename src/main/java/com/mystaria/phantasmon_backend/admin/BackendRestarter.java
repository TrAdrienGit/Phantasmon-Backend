package com.mystaria.phantasmon_backend.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.mystaria.phantasmon_backend.PhantasmonBackendApplication;

import lombok.extern.slf4j.Slf4j;

/**
 * Admin reboot (TODO-25). The backend is started by hand ({@code java -jar}), with nothing around it to start it
 * again, so it restarts itself in the same process: the Spring context is closed (live battles end as draws,
 * {@code BACKEND_LOST}) then started again with the original arguments — configuration, admin file and Showdown
 * rules are read afresh. Clients reconnect on their own.
 */
@Component
@Slf4j
public class BackendRestarter {

	private final ConfigurableApplicationContext context;

	public BackendRestarter(ConfigurableApplicationContext context) {
		this.context = context;
	}

	/** Shortly after the current request is answered. */
	public void restartSoon() {
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			log.warn("Restarting the backend...");
			context.close();
			SpringApplication.run(PhantasmonBackendApplication.class, PhantasmonBackendApplication.arguments());
		}, "phantasmon-restart");
		thread.setDaemon(false);
		thread.start();
	}
}
