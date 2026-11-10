package com.mystaria.phantasmon_backend.hub;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * An Emotecraft emote's full animation (2026-11-10), so the hub members who lack that emote still see it, as Emotecraft
 * does through a Minecraft server. Binary WebSocket messages; the backend never reads the animation:
 * <ul>
 * <li>C2S: {@code [kind 0x02][animation: the client's own encoding, at most MAX_BYTES]};</li>
 * <li>S2C: {@code [kind 0x02][sender uuid: 16 bytes][animation]}.</li>
 * </ul>
 */
public final class HubEmoteFrame {

	public static final byte KIND_EMOTE = 0x02;
	/** Emotes weigh a few KiB to a few tens; anything bigger is refused (the others then play it only if they have it). */
	public static final int MAX_BYTES = 64 * 1024;

	private HubEmoteFrame() {
	}

	/** The animation bytes of a client's emote frame, or null if it is not one / empty / too big. */
	public static byte[] parse(ByteBuffer payload) {
		ByteBuffer in = payload.duplicate();
		if (in.remaining() <= 1 || in.remaining() > 1 + MAX_BYTES || in.get() != KIND_EMOTE) {
			return null;
		}
		byte[] animation = new byte[in.remaining()];
		in.get(animation);
		return animation;
	}

	/** The frame as the others receive it, with who plays it. */
	public static byte[] toListeners(UUID sender, byte[] animation) {
		return ByteBuffer.allocate(1 + 16 + animation.length).put(KIND_EMOTE)
				.putLong(sender.getMostSignificantBits()).putLong(sender.getLeastSignificantBits()).put(animation).array();
	}
}
