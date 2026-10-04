package com.mystaria.phantasmon_backend.websocket;

import java.util.function.LongSupplier;

/**
 * Per-connection token bucket for incoming WebSocket messages (SEC-5, security audit 2026-10-04): {@code ratePerSecond}
 * tokens come back every second, up to {@code capacity}. Generous on purpose — one battle turn relays a burst of
 * Cobblemon packets — but it stops a modified client from flooding the backend and, through the relays, the other
 * players. One instance per session; calls come from that session's handler thread.
 */
final class MessageRateLimiter {

	private static final double NANOS_PER_SECOND = 1_000_000_000.0;

	private final double ratePerSecond;
	private final double capacity;
	private final LongSupplier nanoTime;
	private double tokens;
	private long lastRefill;
	private boolean refusing;
	private boolean refusalReported;

	MessageRateLimiter(double ratePerSecond, double capacity, LongSupplier nanoTime) {
		this.ratePerSecond = ratePerSecond;
		this.capacity = capacity;
		this.nanoTime = nanoTime;
		this.tokens = capacity;
		this.lastRefill = nanoTime.getAsLong();
	}

	synchronized boolean tryAcquire() {
		long now = nanoTime.getAsLong();
		tokens = Math.min(capacity, tokens + (now - lastRefill) / NANOS_PER_SECOND * ratePerSecond);
		lastRefill = now;
		if (tokens >= 1) {
			tokens -= 1;
			refusing = false;
			refusalReported = false;
			return true;
		}
		refusing = true;
		return false;
	}

	/** True once per streak of refusals, so the client gets one error, not one per dropped message. */
	synchronized boolean shouldReportRefusal() {
		if (refusing && !refusalReported) {
			refusalReported = true;
			return true;
		}
		return false;
	}
}
