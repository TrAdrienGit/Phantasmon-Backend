package com.mystaria.phantasmon_backend.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * Logs every REST call (method, path, resulting status, duration). This is
 * the "toutes les opérations... via l'API" half of CAD Partie 2 §17's
 * logging requirement; WebSocket events are logged where they are handled
 * ({@code PhantasmonWebSocketHandler}, {@code LiveTradeService},
 * {@code LiveBattleService}...).
 *
 * <p>This filter always logs through SLF4J regardless of the
 * {@code phantasmon.logging.enabled} toggle — whether that ends up in the
 * per-session file is decided once, centrally, by
 * {@link SessionLogFileEnvironmentPostProcessor} (Spring Boot's
 * {@code logging.file.name}), not repeated here.
 */
@Component
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

	/** A poll slower than this is logged even when it succeeds. */
	static final long SLOW_POLL_MILLIS = 500;

	static boolean isPoll(String uri) {
		return "/health".equals(uri) || (uri.startsWith("/server/mirror/") && uri.endsWith("/feed"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long start = System.currentTimeMillis();
		try {
			filterChain.doFilter(request, response);
		} finally {
			long durationMillis = System.currentTimeMillis() - start;
			String uri = request.getRequestURI();
			// Polls (our Minecraft server reads each mirror hub's feed every second, clients ping /health) made up nearly
			// every line of the log: logged only when they fail or are slow.
			if (isPoll(uri) && response.getStatus() < 400 && durationMillis < SLOW_POLL_MILLIS) {
				log.debug("{} {} -> {} ({} ms)", request.getMethod(), uri, response.getStatus(), durationMillis);
			} else {
				log.info("{} {} -> {} ({} ms)", request.getMethod(), uri, response.getStatus(), durationMillis);
			}
		}
	}
}
