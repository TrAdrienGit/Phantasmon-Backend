package com.mystaria.phantasmon_backend.websocket;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.mystaria.phantasmon_backend.auth.JwtService;

/**
 * Authenticates the WebSocket handshake via {@code ?token=<access_token>}
 * (CAD Partie 2 §11) — rejects the upgrade outright (401) on a missing,
 * malformed, expired, or wrong-typed token, before any {@link WebSocketHandler}
 * ever sees the connection.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

	public static final String PLAYER_UUID_ATTRIBUTE = "playerUuid";

	private final JwtService jwtService;

	public JwtHandshakeInterceptor(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
		if (token == null) {
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return false;
		}

		return jwtService.parseAccessToken(token)
				.map(claims -> {
					attributes.put(PLAYER_UUID_ATTRIBUTE, UUID.fromString(claims.getSubject()));
					return true;
				})
				.orElseGet(() -> {
					response.setStatusCode(HttpStatus.UNAUTHORIZED);
					return false;
				});
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
	}
}
