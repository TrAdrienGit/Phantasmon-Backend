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
 * logging requirement; WebSocket events get the same treatment once the
 * {@code websocket} domain exists (there is nothing to instrument yet).
 *
 * <p>This filter always logs through SLF4J regardless of the
 * {@code phantasmon.logging.enabled} toggle — whether that ends up in the
 * per-session file is decided once, centrally, by {@code logback-spring.xml},
 * not repeated here.
 */
@Component
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long start = System.currentTimeMillis();
		try {
			filterChain.doFilter(request, response);
		} finally {
			long durationMillis = System.currentTimeMillis() - start;
			log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), durationMillis);
		}
	}
}
