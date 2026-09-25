package com.mystaria.phantasmon_backend.auth;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

	private static final String SECRET = Base64.getEncoder()
			.encodeToString("test-only-secret-key-not-for-production-use-0123456789".getBytes());

	private final JwtService jwtService = new JwtService(SECRET, Duration.ofMinutes(20), Duration.ofDays(7));
	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void authenticatesRequestWithValidBearerToken() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(playerUuid);
		verify(chain).doFilter((HttpServletRequest) request, (HttpServletResponse) response);
	}

	@Test
	void leavesContextEmptyWhenHeaderIsMissing() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void leavesContextEmptyWhenTokenIsInvalid() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer not-a-real-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}
}
