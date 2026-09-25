package com.mystaria.phantasmon_backend.websocket;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.presence.PresenceService;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "phantasmon.logging.enabled=false")
class PhantasmonWebSocketIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PresenceService presenceService;

	private static class RecordingHandler extends TextWebSocketHandler {
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(message.getPayload());
		}
	}

	private WebSocketSession connect(String token, RecordingHandler handler) throws Exception {
		StandardWebSocketClient client = new StandardWebSocketClient();
		return client.execute(handler, "ws://localhost:" + port + "/ws?token=" + token).get(5, TimeUnit.SECONDS);
	}

	@Test
	void heartbeatReceivesAck() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		try {
			session.sendMessage(new TextMessage("""
					{"type":"Heartbeat","data":{}}
					"""));
			String reply = handler.received.poll(5, TimeUnit.SECONDS);

			assertThat(reply).contains("HeartbeatAck");
		} finally {
			session.close();
		}
	}

	@Test
	void joinServerGroupRecordsPresence() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		try {
			session.sendMessage(new TextMessage("""
					{"type":"JoinServerGroup","data":{"server_fingerprint":"fp-1","dimension":"minecraft:overworld"}}
					"""));
			Thread.sleep(300);

			assertThat(presenceService.find(playerUuid)).isPresent();
			assertThat(presenceService.find(playerUuid).get().serverFingerprint()).isEqualTo("fp-1");
		} finally {
			session.close();
		}
	}

	@Test
	void disconnectRemovesPresence() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		session.sendMessage(new TextMessage("""
				{"type":"JoinServerGroup","data":{"server_fingerprint":"fp-1","dimension":"minecraft:overworld"}}
				"""));
		Thread.sleep(300);
		assertThat(presenceService.find(playerUuid)).isPresent();

		session.close(CloseStatus.NORMAL);
		Thread.sleep(300);

		assertThat(presenceService.find(playerUuid)).isEmpty();
	}

	@Test
	void unknownMessageTypeReturnsError() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		try {
			session.sendMessage(new TextMessage("""
					{"type":"NotARealType","data":{}}
					"""));
			String reply = handler.received.poll(5, TimeUnit.SECONDS);

			assertThat(reply).contains("ERROR_WS_UNKNOWN_MESSAGE_TYPE");
		} finally {
			session.close();
		}
	}

	@Test
	void handshakeIsRejectedWithoutAValidToken() {
		StandardWebSocketClient client = new StandardWebSocketClient();
		RecordingHandler handler = new RecordingHandler();

		org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> client.execute(handler, "ws://localhost:" + port + "/ws?token=not-a-real-token")
						.get(5, TimeUnit.SECONDS));
	}
}
