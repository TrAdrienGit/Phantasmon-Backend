package com.mystaria.phantasmon_backend.hub;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Just enough of Minecraft's NBT format to read a schematic's dimensions (the backend has no Minecraft code): a
 * gzip-compressed (or plain) named root compound, read into plain Java values — {@code Map} for compounds,
 * {@code List} for lists, boxed numbers, {@code String}, and arrays for the three array tags. Big-endian, string
 * lengths as unsigned shorts, modified UTF-8 read as UTF-8 (schematic keys are ASCII).
 */
final class NbtReader {

	private static final int MAX_DEPTH = 512;

	private NbtReader() {
	}

	/** The root compound's content; its name (Sponge v2 calls it "Schematic") is returned under {@code ""}. */
	static Map<String, Object> read(byte[] file) throws IOException {
		InputStream raw = new ByteArrayInputStream(file);
		boolean gzip = file.length > 1 && (file[0] & 0xFF) == 0x1F && (file[1] & 0xFF) == 0x8B;
		try (DataInputStream in = new DataInputStream(gzip ? new GZIPInputStream(raw) : raw)) {
			int type = in.readUnsignedByte();
			if (type != 10) {
				throw new IOException("NBT root is not a compound (tag " + type + ")");
			}
			String name = readString(in);
			@SuppressWarnings("unchecked")
			Map<String, Object> root = (Map<String, Object>) readPayload(in, type, 0);
			Map<String, Object> named = new LinkedHashMap<>();
			named.put("", name);
			named.putAll(root);
			return named;
		}
	}

	private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("NBT nested too deeply");
		}
		return switch (type) {
			case 1 -> in.readByte();
			case 2 -> in.readShort();
			case 3 -> in.readInt();
			case 4 -> in.readLong();
			case 5 -> in.readFloat();
			case 6 -> in.readDouble();
			case 7 -> {
				byte[] bytes = new byte[length(in)];
				in.readFully(bytes);
				yield bytes;
			}
			case 8 -> readString(in);
			case 9 -> {
				int elementType = in.readUnsignedByte();
				int size = length(in);
				List<Object> list = new ArrayList<>();
				for (int i = 0; i < size; i++) {
					list.add(readPayload(in, elementType, depth + 1));
				}
				yield list;
			}
			case 10 -> {
				Map<String, Object> compound = new LinkedHashMap<>();
				for (int child = in.readUnsignedByte(); child != 0; child = in.readUnsignedByte()) {
					String key = readString(in);
					compound.put(key, readPayload(in, child, depth + 1));
				}
				yield compound;
			}
			case 11 -> {
				int[] ints = new int[length(in)];
				for (int i = 0; i < ints.length; i++) {
					ints[i] = in.readInt();
				}
				yield ints;
			}
			case 12 -> {
				long[] longs = new long[length(in)];
				for (int i = 0; i < longs.length; i++) {
					longs[i] = in.readLong();
				}
				yield longs;
			}
			default -> throw new IOException("Unknown NBT tag " + type);
		};
	}

	private static int length(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0) {
			throw new IOException("Negative NBT length");
		}
		return length;
	}

	private static String readString(DataInputStream in) throws IOException {
		byte[] bytes = new byte[in.readUnsignedShort()];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
