package com.mystaria.phantasmon_backend.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class RequestLoggingFilterTest {

	private final RequestLoggingFilter filter = new RequestLoggingFilter();
	private ListAppender<ILoggingEvent> logAppender;
	private Logger filterLogger;

	@BeforeEach
	void attachLogCapture() {
		filterLogger = (Logger) org.slf4j.LoggerFactory.getLogger(RequestLoggingFilter.class);
		logAppender = new ListAppender<>();
		logAppender.start();
		filterLogger.addAppender(logAppender);
	}

	@AfterEach
	void detachLogCapture() {
		filterLogger.detachAppender(logAppender);
	}

	@Test
	void logsMethodPathStatusAndAlwaysContinuesTheChain() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(200);
		FilterChain chain = Mockito.mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertThat(logAppender.list).hasSize(1);
		String message = logAppender.list.get(0).getFormattedMessage();
		assertThat(message).contains("GET").contains("/health").contains("200");
	}

	@Test
	void logsEvenWhenTheChainThrows() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/session");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(500);
		FilterChain chain = (req, res) -> {
			throw new RuntimeException("boom");
		};

		try {
			filter.doFilter(request, response, chain);
		} catch (Exception expected) {
			// propagation is correct behaviour, we only care that logging still happened
		}

		assertThat(logAppender.list).hasSize(1);
		assertThat(logAppender.list.get(0).getFormattedMessage()).contains("POST").contains("/auth/session");
	}
}
