package com.mystaria.phantasmon_backend.hub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * The Global Hub's build (D-34): the one schematic in {@code <schematics-dir>/hub_global/}, a Sponge {@code .schem}
 * (v1 to v3, WorldEdit) or a Litematica {@code .litematic}, that every client builds as client-only blocks in each
 * Global Hub anchor's cube. Read once at startup and checked there: exactly one such file, readable, and exactly
 * {@code anchor-size} blocks on each side — otherwise the backend refuses to start (Adrien's rule), with the reason.
 * The backend never reads the blocks themselves: it serves the file as is ({@code GET /hub/schematic/file}), with
 * its SHA-256 so clients cache it.
 */
@Service
@Slf4j
public class HubSchematicService {

	static final String GLOBAL_HUB_FOLDER = "hub_global";

	public enum Format {
		SCHEM, LITEMATIC
	}

	public record Schematic(String fileName, Format format, String sha256, int sizeX, int sizeY, int sizeZ, byte[] bytes) {
	}

	private final Schematic global;

	public HubSchematicService(@Value("${phantasmon.hub.schematics-dir:hub_schematics}") String schematicsDir,
			@Value("${phantasmon.hub.anchor-size:21}") int anchorSize) {
		this.global = load(Path.of(schematicsDir, GLOBAL_HUB_FOLDER), anchorSize);
		log.info("Global Hub schematic {} ({}, {}x{}x{}, sha256 {})", global.fileName(), global.format(), global.sizeX(),
				global.sizeY(), global.sizeZ(), global.sha256());
	}

	public Schematic global() {
		return global;
	}

	static Schematic load(Path folder, int anchorSize) {
		List<Path> files;
		try (Stream<Path> entries = Files.list(folder)) {
			files = entries.filter(Files::isRegularFile).filter(path -> formatOf(path) != null).sorted().toList();
		} catch (IOException ex) {
			throw new IllegalStateException("Global Hub schematic folder missing or unreadable: " + folder.toAbsolutePath()
					+ " — create it and put exactly one .schem or .litematic file in it", ex);
		}
		if (files.size() != 1) {
			throw new IllegalStateException("Global Hub schematic folder " + folder.toAbsolutePath() + " must hold exactly one "
					+ ".schem or .litematic file, found " + files.size() + (files.isEmpty() ? "" : ": " + files));
		}
		Path file = files.get(0);
		Format format = formatOf(file);
		byte[] bytes;
		int[] size;
		try {
			bytes = Files.readAllBytes(file);
			size = dimensions(NbtReader.read(bytes), format);
		} catch (IOException | RuntimeException ex) {
			throw new IllegalStateException("Global Hub schematic " + file.toAbsolutePath() + " cannot be read: " + ex.getMessage(), ex);
		}
		if (size[0] != anchorSize || size[1] != anchorSize || size[2] != anchorSize) {
			throw new IllegalStateException("Global Hub schematic " + file.getFileName() + " is " + size[0] + "x" + size[1] + "x"
					+ size[2] + " blocks; a Hub anchor is " + anchorSize + "x" + anchorSize + "x" + anchorSize
					+ " (phantasmon.hub.anchor-size): both must match");
		}
		return new Schematic(file.getFileName().toString(), format, sha256(bytes), size[0], size[1], size[2], bytes);
	}

	private static Format formatOf(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".schem") ? Format.SCHEM : name.endsWith(".litematic") ? Format.LITEMATIC : null;
	}

	/** Width (x), height (y), length (z). */
	@SuppressWarnings("unchecked")
	static int[] dimensions(Map<String, Object> root, Format format) {
		if (format == Format.LITEMATIC) {
			Map<String, Object> size = (Map<String, Object>) ((Map<String, Object>) root.get("Metadata")).get("EnclosingSize");
			return new int[] { Math.abs(number(size, "x")), Math.abs(number(size, "y")), Math.abs(number(size, "z")) };
		}
		// Sponge v3 nests everything in a "Schematic" compound; v1/v2 name the root itself "Schematic".
		Map<String, Object> schematic = root.get("Schematic") instanceof Map<?, ?> nested ? (Map<String, Object>) nested : root;
		return new int[] { number(schematic, "Width"), number(schematic, "Height"), number(schematic, "Length") };
	}

	private static int number(Map<String, Object> compound, String key) {
		if (!(compound.get(key) instanceof Number number)) {
			throw new IllegalArgumentException("missing " + key);
		}
		// Sponge stores the sizes as unsigned shorts.
		return number instanceof Short s ? Short.toUnsignedInt(s) : number.intValue();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
