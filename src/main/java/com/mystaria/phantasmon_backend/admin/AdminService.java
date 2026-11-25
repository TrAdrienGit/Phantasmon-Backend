package com.mystaria.phantasmon_backend.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;

import lombok.extern.slf4j.Slf4j;

/**
 * Who is an admin (TODO-25, Adrien 2026-10-06): like a Minecraft server's ops, a plain text file next to the backend
 * ({@code phantasmon.admin.file}, default {@code admins.txt}) with one Minecraft name per line, case-insensitive,
 * {@code #} for comments. Read again whenever it changes — no restart needed. A player is matched by the name of
 * their last login. Missing file: no admin (a commented template is written).
 * <p>
 * Admins also manage the list from the game (2026-11-25, Phantasmon menu, Admins page): {@link #list}, {@link #add} and
 * {@link #remove} rewrite the same file, its comments kept.
 */
@Service
@Slf4j
public class AdminService {

	private static final String TEMPLATE = """
			# Phantasmon admins — one Minecraft player name per line (case doesn't matter), like a server's ops.
			# Changes are picked up without restarting the backend.
			""";

	private final Path file;
	private final PlayerService playerService;
	private Set<String> admins = Set.of();
	private FileTime readAt;

	public AdminService(@Value("${phantasmon.admin.file:admins.txt}") String file, PlayerService playerService) {
		this.file = Path.of(file);
		this.playerService = playerService;
		if (!Files.exists(this.file)) {
			try {
				if (this.file.getParent() != null) {
					Files.createDirectories(this.file.getParent());
				}
				Files.writeString(this.file, TEMPLATE, StandardCharsets.UTF_8);
				log.info("Admin file created (empty): {}", this.file.toAbsolutePath());
			} catch (IOException ex) {
				log.warn("Cannot create the admin file {}", this.file.toAbsolutePath(), ex);
			}
		}
	}

	public boolean isAdmin(UUID playerUuid) {
		String name = playerService.findById(playerUuid).map(Player::getLastUsername).orElse(null);
		return name != null && names().contains(name.toLowerCase(Locale.ROOT));
	}

	/** Refuses with {@code ERROR_ADMIN_REQUIRED} (403) unless {@code playerUuid} is an admin. */
	public void requireAdmin(UUID playerUuid) {
		if (!isAdmin(playerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_ADMIN_REQUIRED", Map.of());
		}
	}

	private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");

	/** The admins' names as written in the file, in its order. */
	public synchronized List<String> list() {
		List<String> out = new ArrayList<>();
		for (String line : lines()) {
			String name = line.strip();
			if (!name.isEmpty() && !name.startsWith("#")) {
				out.add(name);
			}
		}
		return out;
	}

	/** Adds {@code name} (a Minecraft name, who may never have logged in yet) at the end of the file. */
	public synchronized List<String> add(UUID by, String name) {
		String wanted = name == null ? "" : name.strip();
		if (!NAME.matcher(wanted).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_ADMIN_NAME_INVALID", Map.of("name", wanted));
		}
		if (names().contains(wanted.toLowerCase(Locale.ROOT))) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_ADMIN_ALREADY", Map.of("name", wanted));
		}
		List<String> lines = new ArrayList<>(lines());
		lines.add(wanted);
		write(lines);
		log.info("Admin {} added admin {}", by, wanted);
		return list();
	}

	/** Removes {@code name}; an admin cannot remove themself (the list would lose its last way in otherwise). */
	public synchronized List<String> remove(UUID by, String name) {
		String key = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
		if (!names().contains(key)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ERROR_ADMIN_NOT_FOUND", Map.of("name", name == null ? "" : name));
		}
		String self = playerService.findById(by).map(Player::getLastUsername).orElse("");
		if (self.toLowerCase(Locale.ROOT).equals(key)) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_ADMIN_SELF", Map.of());
		}
		List<String> lines = new ArrayList<>(lines());
		lines.removeIf(line -> line.strip().toLowerCase(Locale.ROOT).equals(key));
		write(lines);
		log.info("Admin {} removed admin {}", by, name);
		return list();
	}

	private List<String> lines() {
		try {
			return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
		} catch (IOException ex) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_ADMIN_FILE", Map.of());
		}
	}

	private void write(List<String> lines) {
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			readAt = null; // read again at the next check
		} catch (IOException ex) {
			log.warn("Cannot write the admin file {}", file.toAbsolutePath(), ex);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_ADMIN_FILE", Map.of());
		}
	}

	private synchronized Set<String> names() {
		try {
			FileTime modified = Files.exists(file) ? Files.getLastModifiedTime(file) : null;
			if (modified == null) {
				admins = Set.of();
				readAt = null;
			} else if (!modified.equals(readAt)) {
				Set<String> read = new HashSet<>();
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					String name = line.strip();
					if (!name.isEmpty() && !name.startsWith("#")) {
						read.add(name.toLowerCase(Locale.ROOT));
					}
				}
				admins = Set.copyOf(read);
				readAt = modified;
				log.info("Admin file read: {} admin(s)", admins.size());
			}
		} catch (IOException ex) {
			log.warn("Cannot read the admin file {}; keeping the previous list", file.toAbsolutePath(), ex);
		}
		return admins;
	}
}
