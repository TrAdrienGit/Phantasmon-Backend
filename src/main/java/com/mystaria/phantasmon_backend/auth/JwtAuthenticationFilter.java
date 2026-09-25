package com.mystaria.phantasmon_backend.auth;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the Spring Security context from a {@code Bearer} access token
 * issued by {@code POST /auth/session}. Absent/invalid/expired/refresh tokens
 * simply leave the request unauthenticated — {@link org.springframework.security.web.SecurityFilterChain}
 * rules decide whether that's acceptable for the target route.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			String token = header.substring(BEARER_PREFIX.length());
			jwtService.parseAccessToken(token).ifPresent(claims -> {
				UUID playerUuid = UUID.fromString(claims.getSubject());
				var authentication = new UsernamePasswordAuthenticationToken(
						playerUuid, null, List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			});
		}
		filterChain.doFilter(request, response);
	}
}
