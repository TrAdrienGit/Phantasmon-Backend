package com.mystaria.phantasmon_backend.hub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * The hubs (D-35) and their builds (D-34). Admins create a hub with a name and a size, which also creates its folder
 * {@code <schematics-dir>/hub_<name>/}; they drop at most one {@code .schem} (Sponge v1 to v3) or {@code .litematic}
 * of exactly the hub's size in it and reload the hub. A hub with an empty folder works without any build.
 *
 * <p>Checked at startup: a hub folder holding several schematics, an unreadable one or one of the wrong size stops
 * the backend (Adrien's rule); a reload with such a folder is refused and keeps the previous build. The backend never
 * reads the blocks: it serves the file as is, with its SHA-256 so clients cache it. Every change (hub created,
 * deleted, reloaded) is announced to every connected client ({@code HubCatalogChanged}).
 */
@Service
@Slf4j
public class HubCatalogService {

	/** Lower-case letters, digits and {@code _}: the name is also a folder name. */
	static final Pattern NAME = Pattern.compile("[a-z0-9_]{2,32}");
	static final int MIN_SIZE = 3;
	static final int MAX_SIZE = 64;
	private static final DateTimeFormatter ARCHIVE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	public enum Format {
		SCHEM, LITEMATIC
	}

	public record Schematic(String fileName, Format format, String sha256, int sizeX, int sizeY, int sizeZ, byte[] bytes) {
	}

	/** A hub as the API shows it; {@code schematic} is null for a hub without a build. */
	public record HubView(String name, Size size, SchematicView schematic) {

		public record Size(int x, int y, int z) {
		}

		public record SchematicView(String name, Format format, String sha256, int bytes) {
		}
	}

	private final HubRepository hubRepository;
	private final HubAnchorRepository anchorRepository;
	private final HubService hubService;
	private final SessionRegistry sessionRegistry;
	private final Path root;
	/** Hub uuid → its build, absent for a hub without one. */
	private final Map<UUID, Schematic> schematics = new ConcurrentHashMap<>();

	public HubCatalogService(HubRepository hubRepository, HubAnchorRepository anchorRepository, HubService hubService,
			SessionRegistry sessionRegistry, @Value("${phantasmon.hub.schematics-dir:hub_schematics}") String schematicsDir) {
		this.hubRepository = hubRepository;
		this.anchorRepository = anchorRepository;
		this.hubService = hubService;
		this.sessionRegistry = sessionRegistry;
		this.root = Path.of(schematicsDir);
	}

	/** Startup: every hub's folder is created if missing and its build loaded — or the backend refuses to start. */
	@PostConstruct
	void loadAll() {
		for (Hub hub : hubRepository.findAllByOrderByNameAsc()) {
			Path folder = folderOf(hub.getName());
			try {
				Files.createDirectories(folder);
			} catch (IOException ex) {
				throw new IllegalStateException("Cannot create the schematic folder of hub " + hub.getName() + ": "
						+ folder.toAbsolutePath(), ex);
			}
			Optional<Schematic> schematic = load(folder, hub);
			if (schematic.isPresent()) {
				Schematic s = schematic.get();
				schematics.put(hub.getUuid(), s);
				log.info("Hub {} ({}x{}x{}): build {} ({}, sha256 {})", hub.getName(), hub.getSizeX(), hub.getSizeY(),
						hub.getSizeZ(), s.fileName(), s.format(), s.sha256());
			} else {
				log.info("Hub {} ({}x{}x{}): no build in {}", hub.getName(), hub.getSizeX(), hub.getSizeY(), hub.getSizeZ(),
						folder.toAbsolutePath());
			}
		}
	}

	// ---------------------------------------------------------------- reading

	@Transactional(readOnly = true)
	public List<HubView> list() {
		return hubRepository.findAllByOrderByNameAsc().stream().map(this::view).toList();
	}

	@Transactional(readOnly = true)
	public Hub require(String name) {
		String key = name == null ? "" : name.strip();
		return hubRepository.findByNameIgnoreCase(key).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_HUB_NOT_FOUND", Map.of("hub", key)));
	}

	@Transactional(readOnly = true)
	public Schematic schematicOf(String name) {
		Hub hub = require(name);
		Schematic schematic = schematics.get(hub.getUuid());
		if (schematic == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ERROR_HUB_SCHEMATIC_NONE", Map.of("hub", hub.getName()));
		}
		return schematic;
	}

	HubView view(Hub hub) {
		Schematic schematic = schematics.get(hub.getUuid());
		return new HubView(hub.getName(), new HubView.Size(hub.getSizeX(), hub.getSizeY(), hub.getSizeZ()),
				schematic == null ? null : new HubView.SchematicView(schematic.fileName(), schematic.format(),
						schematic.sha256(), schematic.bytes().length));
	}

	// ---------------------------------------------------------------- admin

	/** A new hub, {@code width} (x) × {@code height} (y) × {@code length} (z), and its empty schematic folder. */
	@Transactional
	public HubView create(UUID adminUuid, String rawName, Integer length, Integer width, Integer height) {
		String name = rawName == null ? "" : rawName.strip().toLowerCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_HUB_INVALID_NAME", Map.of("name", name));
		}
		for (Integer size : new Integer[] { length, width, height }) {
			if (size == null || size < MIN_SIZE || size > MAX_SIZE) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_HUB_INVALID_SIZE", Map.of("min", MIN_SIZE, "max", MAX_SIZE));
			}
		}
		if (hubRepository.findByNameIgnoreCase(name).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_NAME_TAKEN", Map.of("hub", name));
		}
		Hub hub = hubRepository.saveAndFlush(new Hub(UUID.randomUUID(), name, width, height, length, adminUuid));
		Path folder = folderOf(name);
		try {
			Files.createDirectories(folder);
		} catch (IOException ex) {
			log.error("Hub {} created but its schematic folder {} could not be", name, folder.toAbsolutePath(), ex);
		}
		log.info("Admin {} created hub {} ({}x{}x{}), schematic folder {}", adminUuid, name, width, height, length,
				folder.toAbsolutePath());
		announce();
		return view(hub);
	}

	/**
	 * Deletes the hub: its anchors go (their builds disappear, their members leave the hub) and its folder is kept as
	 * {@code hub_<name>.deleted-<date>}. Returns how many anchors were deleted and the archive's name.
	 */
	@Transactional
	public Map<String, Object> delete(UUID adminUuid, String name) {
		Hub hub = require(name);
		List<HubAnchor> anchors = anchorRepository.findByHubUuid(hub.getUuid());
		anchorRepository.deleteAll(anchors);
		hubRepository.delete(hub);
		hubRepository.flush();
		anchors.forEach(anchor -> hubService.onAnchorDeleted(anchor.getUuid()));
		schematics.remove(hub.getUuid());
		Path folder = folderOf(hub.getName());
		String archive = null;
		if (Files.isDirectory(folder)) {
			Path target = folder.resolveSibling(folder.getFileName() + ".deleted-" + LocalDateTime.now().format(ARCHIVE_STAMP));
			try {
				Files.move(folder, target);
				archive = target.getFileName().toString();
			} catch (IOException ex) {
				log.error("Hub {} deleted but its folder {} could not be archived", hub.getName(), folder.toAbsolutePath(), ex);
			}
		}
		log.info("Admin {} deleted hub {} ({} anchor(s)), folder archived as {}", adminUuid, hub.getName(), anchors.size(), archive);
		announce();
		Map<String, Object> result = new HashMap<>();
		result.put("hub", hub.getName());
		result.put("anchors_deleted", anchors.size());
		result.put("archived_as", archive);
		return result;
	}

	/** Reads the hub's folder again; a folder the startup would refuse is refused here, the previous build kept. */
	@Transactional(readOnly = true)
	public HubView reload(UUID adminUuid, String name) {
		Hub hub = require(name);
		Optional<Schematic> schematic;
		try {
			Files.createDirectories(folderOf(hub.getName()));
			schematic = load(folderOf(hub.getName()), hub);
		} catch (IOException | IllegalStateException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ERROR_HUB_SCHEMATIC_INVALID",
					Map.of("hub", hub.getName(), "reason", String.valueOf(ex.getMessage())));
		}
		if (schematic.isPresent()) {
			schematics.put(hub.getUuid(), schematic.get());
		} else {
			schematics.remove(hub.getUuid());
		}
		log.info("Admin {} reloaded hub {}: {}", adminUuid, hub.getName(), schematic.map(Schematic::fileName).orElse("no build"));
		announce();
		return view(hub);
	}

	private void announce() {
		sessionRegistry.broadcast(WsMessage.of("HubCatalogChanged", Map.of()));
	}

	// ---------------------------------------------------------------- files

	Path folderOf(String hubName) {
		return root.resolve("hub_" + hubName.toLowerCase(Locale.ROOT));
	}

	/** The folder's build: none if it holds no schematic; refused (exception) if several, unreadable or of the wrong size. */
	static Optional<Schematic> load(Path folder, Hub hub) {
		List<Path> files;
		try (Stream<Path> entries = Files.list(folder)) {
			files = entries.filter(Files::isRegularFile).filter(path -> formatOf(path) != null).sorted().toList();
		} catch (IOException ex) {
			throw new IllegalStateException("Schematic folder of hub " + hub.getName() + " unreadable: " + folder.toAbsolutePath(), ex);
		}
		if (files.isEmpty()) {
			return Optional.empty();
		}
		if (files.size() > 1) {
			throw new IllegalStateException("Schematic folder " + folder.toAbsolutePath() + " of hub " + hub.getName()
					+ " must hold at most one .schem or .litematic file, found " + files.size() + ": " + files);
		}
		Path file = files.get(0);
		Format format = formatOf(file);
		byte[] bytes;
		int[] size;
		try {
			bytes = Files.readAllBytes(file);
			size = dimensions(NbtReader.read(bytes), format);
		} catch (IOException | RuntimeException ex) {
			throw new IllegalStateException("Schematic " + file.toAbsolutePath() + " cannot be read: " + ex.getMessage(), ex);
		}
		if (size[0] != hub.getSizeX() || size[1] != hub.getSizeY() || size[2] != hub.getSizeZ()) {
			throw new IllegalStateException("Schematic " + file.getFileName() + " is " + size[0] + "x" + size[1] + "x" + size[2]
					+ " blocks (width x height x length); hub " + hub.getName() + " is " + hub.getSizeX() + "x" + hub.getSizeY()
					+ "x" + hub.getSizeZ() + ": both must match");
		}
		return Optional.of(new Schematic(file.getFileName().toString(), format, sha256(bytes), size[0], size[1], size[2], bytes));
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
