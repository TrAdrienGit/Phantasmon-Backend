package com.mystaria.phantasmon_backend.hub;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * The hub voice frames (D-36): binary WebSocket messages, so the 50 voice packets a second of each speaker carry no
 * JSON nor base64. The backend never decodes the audio (Opus, encoded and played by the clients' Simple Voice Chat).
 *
 * <ul>
 * <li>C2S: {@code [kind 0x01][flags][seq int32][opus bytes]};</li>
 * <li>S2C: {@code [kind 0x01][flags][seq int32][speaker uuid: 16 bytes][opus bytes]}.</li>
 * </ul>
 * {@code flags} bit 0: whispering. Big-endian.
 */
public final class HubVoice {

	public static final byte KIND_VOICE = 0x01;
	public static final int FLAG_WHISPERING = 0x01;
	/** An Opus frame of 20 ms is far smaller; anything bigger is not voice. */
	public static final int MAX_AUDIO_BYTES = 1024;
	static final int CLIENT_HEADER = 1 + 1 + 4;
	static final int SERVER_HEADER = CLIENT_HEADER + 16;

	private HubVoice() {
	}

	/** A client's voice frame, or null if it is not one / malformed / too big. */
	public static Frame parse(ByteBuffer payload) {
		ByteBuffer in = payload.duplicate();
		if (in.remaining() <= CLIENT_HEADER || in.remaining() > CLIENT_HEADER + MAX_AUDIO_BYTES || in.get() != KIND_VOICE) {
			return null;
		}
		int flags = in.get() & 0xFF;
		int seq = in.getInt();
		byte[] audio = new byte[in.remaining()];
		in.get(audio);
		return new Frame(flags, seq, audio);
	}

	public record Frame(int flags, int seq, byte[] audio) {

		/** The frame as the listeners receive it, with who speaks. */
		public byte[] toListeners(UUID speaker) {
			ByteBuffer out = ByteBuffer.allocate(SERVER_HEADER + audio.length);
			out.put(KIND_VOICE).put((byte) flags).putInt(seq)
					.putLong(speaker.getMostSignificantBits()).putLong(speaker.getLeastSignificantBits())
					.put(audio);
			return out.array();
		}
	}
}
