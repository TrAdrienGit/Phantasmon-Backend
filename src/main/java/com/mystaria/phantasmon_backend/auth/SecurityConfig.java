package com.mystaria.phantasmon_backend.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Baseline security chain: only the routes the OpenAPI contract marks as
 * {@code security: []} (health, version, auth/session, auth/refresh) are
 * public. Everything else requires a valid JWT access token
 * ({@link JwtAuthenticationFilter}); there is no session/form login, the API
 * is stateless (CAD Partie 2 §3).
 *
 * <p>{@code /ws} is also {@code permitAll()} here — the WebSocket *handshake*
 * is a normal HTTP GET this filter chain would otherwise reject (the client
 * has no way to set an {@code Authorization} header on a WS upgrade; the JWT
 * travels as a {@code ?token=} query param instead, per CAD Partie 2 §11).
 * Its own authentication is done separately, by a
 * {@code JwtHandshakeInterceptor} that rejects the handshake outright on a
 * missing/invalid token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, "/health", "/version").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/session", "/auth/refresh").permitAll()
						.requestMatchers("/ws").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
		return new JwtAuthenticationFilter(jwtService);
	}
}
