package com.mystaria.phantasmon_backend.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final PhantasmonWebSocketHandler handler;
	private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

	public WebSocketConfig(PhantasmonWebSocketHandler handler, JwtHandshakeInterceptor jwtHandshakeInterceptor) {
		this.handler = handler;
		this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws")
				.addInterceptors(jwtHandshakeInterceptor)
				.setAllowedOrigins("*");
	}
}
