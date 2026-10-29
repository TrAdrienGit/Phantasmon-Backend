package com.mystaria.phantasmon_backend.events;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.admin.AdminService;
import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.hub.Hub;
import com.mystaria.phantasmon_backend.hub.HubAnchor;
import com.mystaria.phantasmon_backend.hub.HubAnchorRepository;
import com.mystaria.phantasmon_backend.hub.HubRepository;
import com.mystaria.phantasmon_backend.presence.PlayerPresence;
import com.mystaria.phantasmon_backend.presence.PresenceService;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

/**
 * The terrain of the mirror hubs (D-38, Phantasmon Events): the reference copy of each source zone, fed by any
 * Phantasmon player on that server, and sent to the admins building it in their single-player worlds.
 *
 * <p>No size limit (Adrien 2026-10-29), so the terrain is kept by <b>tiles</b> of 16 × 16 blocks, the whole height
 * (384), relative to the zone's north-west bottom corner ({@code x} east, {@code y} up from y -64, {@code z} south), each
 * in 24 sections of 16³ that are either unknown, one block (air, stone…) or an array of ids into the hub's palette of
 * block state strings ({@code minecraft:oak_stairs[facing=north,...]}). Mirror anchors are never turned.
 *
 * <ul>
 * <li>{@code EventsMirrorTile} (a player who read a whole tile whose hash differs from {@link #info}'s): replaces it;
 * the copies are told ({@code EventsMirrorTileChanged}) and fetch it again;</li>
 * <li>{@code EventsMirrorBlocks} (blocks the server changed): applied and relayed to the copies;</li>
 * <li>both carry the hub's {@code radius}: a message for another radius (the hub was just resized) is ignored.</li>
 * </ul>
 * Saved in the hub's folder ({@code mirror.bin}) every 10 s when changed.
 */
@Service
public class MirrorService {

	private static final Logger log = LoggerFactory.getLogger(MirrorService.class);
	static final int TILE = 16;
	static final int SECTIONS = Hub.MIRROR_HEIGHT / 16;
	static final int TILE_VOLUME = TILE * TILE * Hub.MIRROR_HEIGHT;
	private static final int MAX_STATE_LENGTH = 512;
	private static final int MAX_CHANGES = 8192;
	private static final short UNKNOWN = -1;
	private static final int FILE_FORMAT = 2;

	/** {@code GET /events/mirror/{hub}}: the hub's known tiles and their hashes ({@code [tx, tz, hash]}). */
	public record Info(String hub, int radius, int side, long version, List<long[]> tiles) {
	}

	/**
	 * {@code GET /events/mirror/{hub}/tile/{tx}/{tz}}: {@code blocks} is base64 of gzip of {@value #TILE_VOLUME} int16 ids
	 * into {@code palette} (order y, z, x; {@code -1} unknown or outside the zone).
	 */
	public record TileView(int tx, int tz, long version, List<String> palette, String blocks) {
	}

	/** 24 sections: null unknown, a 1-length array for one block everywhere, else 4096 ids. */
	private static final class Tile {
		final short[][] sections = new short[SECTIONS][];
		long hash; // 0: to compute

		short get(int x, int y, int z) {
			short[] section = sections[y >> 4];
			if (section == null) {
				return UNKNOWN;
			}
			return section.length == 1 ? section[0] : section[((y & 15) << 8) | (z << 4) | x];
		}

		void set(int x, int y, int z, short id) {
			int s = y >> 4;
			short[] section = sections[s];
			if (section != null && section.length == 1) {
				if (section[0] == id) {
					return;
				}
				short[] full = new short[4096];
				java.util.Arrays.fill(full, section[0]);
				sections[s] = section = full;
			} else if (section == null) {
				sections[s] = section = new short[4096];
				java.util.Arrays.fill(section, UNKNOWN);
			}
			section[((y & 15) << 8) | (z << 4) | x] = id;
			hash = 0;
		}

		/** Sections of one block made uniform again (memory). */
		void compact() {
			for (int s = 0; s < SECTIONS; s++) {
				short[] section = sections[s];
				if (section == null || section.length == 1) {
					continue;
				}
				boolean uniform = true;
				for (short id : section) {
					if (id != section[0]) {
						uniform = false;
						break;
					}
				}
				if (uniform) {
					sections[s] = section[0] == UNKNOWN ? null : new short[] { section[0] };
				}
			}
		}
	}

	private static final class Terrain {
		final int side;
		final List<String> palette = new ArrayList<>();
		final Map<String, Short> ids = new HashMap<>();
		final Map<Long, Tile> tiles = new HashMap<>();
		long version;
		boolean dirty;

		Terrain(int side) {
			this.side = side;
		}

		short id(String state) {
			return ids.computeIfAbsent(state, key -> {
				palette.add(key);
				return (short) (palette.size() - 1);
			});
		}

		int tiles() {
			return (side + TILE - 1) / TILE;
		}

		/** Zone-relative block, or UNKNOWN outside the zone. */
		short get(int x, int y, int z) {
			Tile tile = tiles.get(key(x / TILE, z / TILE));
			return tile == null ? UNKNOWN : tile.get(x % TILE, y, z % TILE);
		}

		void set(int x, int y, int z, short id) {
			tiles.computeIfAbsent(key(x / TILE, z / TILE), k -> new Tile()).set(x % TILE, y, z % TILE, id);
		}

		boolean inside(int x, int y, int z) {
			return x >= 0 && z >= 0 && y >= 0 && x < side && z < side && y < Hub.MIRROR_HEIGHT;
		}

		/** The tile's hash as the clients compute it, 0 if a block of it in the zone is unknown. */
		long hash(int tx, int tz, Tile tile) {
			if (tile.hash != 0) {
				return tile.hash;
			}
			long hash = 0xcbf29ce484222325L;
			for (int y = 0; y < Hub.MIRROR_HEIGHT; y++) {
				for (int z = 0; z < TILE; z++) {
					for (int x = 0; x < TILE; x++) {
						if (!inside(tx * TILE + x, y, tz * TILE + z)) {
							continue;
						}
						short id = tile.get(x, y, z);
						if (id == UNKNOWN) {
							return 0;
						}
						hash = fnv(hash, palette.get(id));
					}
				}
			}
			tile.hash = hash == 0 ? 1 : hash;
			return tile.hash;
		}
	}

	private final HubRepository hubRepository;
	private final HubAnchorRepository anchorRepository;
	private final PresenceService presenceService;
	private final AdminService adminService;
	private final SessionRegistry sessionRegistry;
	private final Path root;
	private final Map<UUID, Terrain> terrains = new ConcurrentHashMap<>();
	/** Hub → the admins building its copy right now. */
	private final Map<UUID, Set<UUID>> subscribers = new ConcurrentHashMap<>();

	public MirrorService(HubRepository hubRepository, HubAnchorRepository anchorRepository, PresenceService presenceService,
			AdminService adminService, SessionRegistry sessionRegistry,
			@Value("${phantasmon.hub.schematics-dir:hub_schematics}") String schematicsDir) {
		this.hubRepository = hubRepository;
		this.anchorRepository = anchorRepository;
		this.presenceService = presenceService;
		this.adminService = adminService;
		this.sessionRegistry = sessionRegistry;
		this.root = Path.of(schematicsDir);
	}

	/** FNV-1a 64 over the state's UTF-8 bytes and a 0: the same on the clients. */
	static long fnv(long hash, String state) {
		for (byte b : state.getBytes(StandardCharsets.UTF_8)) {
			hash = (hash ^ (b & 0xff)) * 0x100000001b3L;
		}
		return hash * 0x100000001b3L;
	}

	static long key(int tx, int tz) {
		return ((long) tx << 32) | (tz & 0xffffffffL);
	}

	// ---------------------------------------------------------------- reading

	public Info info(String hubName) {
		Hub hub = mirrorHub(hubName);
		Terrain terrain = terrain(hub);
		synchronized (terrain) {
			List<long[]> tiles = new ArrayList<>();
			terrain.tiles.forEach((key, tile) -> tiles.add(new long[] { key >> 32, (int) (long) key,
					terrain.hash((int) (key >> 32), (int) (long) key, tile) }));
			return new Info(hub.getName(), hub.getRadius(), terrain.side, terrain.version, tiles);
		}
	}

	public TileView tile(String hubName, int tx, int tz) {
		Hub hub = mirrorHub(hubName);
		Terrain terrain = terrain(hub);
		synchronized (terrain) {
			Tile tile = terrain.tiles.get(key(tx, tz));
			Map<Short, Short> local = new HashMap<>();
			List<String> palette = new ArrayList<>();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
				for (int y = 0; y < Hub.MIRROR_HEIGHT; y++) {
					for (int z = 0; z < TILE; z++) {
						for (int x = 0; x < TILE; x++) {
							short id = tile == null ? UNKNOWN : tile.get(x, y, z);
							out.writeShort(id == UNKNOWN ? UNKNOWN : local.computeIfAbsent(id, k -> {
								palette.add(terrain.palette.get(k));
								return (short) (palette.size() - 1);
							}));
						}
					}
				}
			} catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			return new TileView(tx, tz, terrain.version, palette, Base64.getEncoder().encodeToString(bytes.toByteArray()));
		}
	}

	// ---------------------------------------------------------------- feeding

	/** {@code EventsMirrorTile}: {@code hub, radius, tx, tz, palette, blocks} (as {@link TileView}). */
	public void receiveTile(UUID playerUuid, Map<String, Object> data) {
		Hub hub = feedable(playerUuid, data);
		if (hub == null || !(data.get("tx") instanceof Number tx) || !(data.get("tz") instanceof Number tz)
				|| !(data.get("palette") instanceof List<?> palette) || !(data.get("blocks") instanceof String blocks)) {
			return;
		}
		Terrain terrain = terrain(hub);
		short[] ids = new short[TILE_VOLUME];
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(blocks))))) {
			for (int i = 0; i < TILE_VOLUME; i++) {
				ids[i] = in.readShort();
			}
		} catch (IOException | IllegalArgumentException ex) {
			return;
		}
		long version;
		synchronized (terrain) {
			int ox = tx.intValue() * TILE;
			int oz = tz.intValue() * TILE;
			if (ox < 0 || oz < 0 || ox >= terrain.side || oz >= terrain.side) {
				return;
			}
			short[] map = new short[palette.size()];
			for (int i = 0; i < palette.size(); i++) {
				if (!(palette.get(i) instanceof String state) || state.isEmpty() || state.length() > MAX_STATE_LENGTH) {
					return;
				}
				map[i] = terrain.id(state);
			}
			boolean changed = false;
			int i = 0;
			for (int y = 0; y < Hub.MIRROR_HEIGHT; y++) {
				for (int z = 0; z < TILE; z++) {
					for (int x = 0; x < TILE; x++, i++) {
						short id = ids[i];
						if (id < 0 || id >= map.length || !terrain.inside(ox + x, y, oz + z)) {
							continue;
						}
						if (terrain.get(ox + x, y, oz + z) != map[id]) {
							terrain.set(ox + x, y, oz + z, map[id]);
							changed = true;
						}
					}
				}
			}
			if (!changed) {
				return;
			}
			Tile tile = terrain.tiles.get(key(tx.intValue(), tz.intValue()));
			if (tile != null) {
				tile.compact();
			}
			version = ++terrain.version;
			terrain.dirty = true;
		}
		publish(hub, WsMessage.of("EventsMirrorTileChanged", Map.of("hub", hub.getName(), "version", version,
				"tx", tx.intValue(), "tz", tz.intValue())));
	}

	/** {@code EventsMirrorBlocks}: {@code hub, radius, changes} ({@code [x, y, z, state]}, zone-relative). */
	public void receiveBlocks(UUID playerUuid, Map<String, Object> data) {
		Hub hub = feedable(playerUuid, data);
		if (hub == null || !(data.get("changes") instanceof List<?> changes) || changes.size() > MAX_CHANGES) {
			return;
		}
		Terrain terrain = terrain(hub);
		List<List<Object>> applied = new ArrayList<>();
		long version;
		synchronized (terrain) {
			for (Object raw : changes) {
				if (!(raw instanceof List<?> change) || change.size() != 4 || !(change.get(0) instanceof Number x)
						|| !(change.get(1) instanceof Number y) || !(change.get(2) instanceof Number z)
						|| !(change.get(3) instanceof String state) || state.isEmpty() || state.length() > MAX_STATE_LENGTH
						|| !terrain.inside(x.intValue(), y.intValue(), z.intValue())) {
					continue;
				}
				short id = terrain.id(state);
				if (terrain.get(x.intValue(), y.intValue(), z.intValue()) != id) {
					terrain.set(x.intValue(), y.intValue(), z.intValue(), id);
					applied.add(List.of(x.intValue(), y.intValue(), z.intValue(), state));
				}
			}
			if (applied.isEmpty()) {
				return;
			}
			version = ++terrain.version;
			terrain.dirty = true;
		}
		publish(hub, WsMessage.of("EventsMirrorBlocks", Map.of("hub", hub.getName(), "version", version, "changes", applied)));
	}

	/** The hub of a feeding message, if the sender is on its source zone's server and dimension and the radius matches. */
	private Hub feedable(UUID playerUuid, Map<String, Object> data) {
		Hub hub = hubRepository.findByNameIgnoreCase(String.valueOf(data.get("hub"))).filter(Hub::isMirror).orElse(null);
		if (hub == null || !(data.get("radius") instanceof Number radius) || radius.intValue() != hub.getRadius()) {
			return null;
		}
		HubAnchor source = anchorRepository.findByHubUuidAndRole(hub.getUuid(), HubAnchor.ROLE_SOURCE).orElse(null);
		PlayerPresence presence = presenceService.find(playerUuid).orElse(null);
		if (source == null || presence == null || !source.getServerFingerprint().equals(presence.serverFingerprint())
				|| !source.getDimension().equals(presence.dimension())) {
			return null;
		}
		return hub;
	}

	private void publish(Hub hub, WsMessage message) {
		subscribers.getOrDefault(hub.getUuid(), Set.of()).forEach(subscriber -> sessionRegistry.send(subscriber, message));
	}

	/** {@code EventsMirrorSubscribe} / {@code EventsMirrorUnsubscribe} {@code {hub}}: an admin building a copy. */
	public void subscribe(UUID playerUuid, Object hubName, boolean subscribe) {
		Hub hub = hubRepository.findByNameIgnoreCase(String.valueOf(hubName)).filter(Hub::isMirror).orElse(null);
		if (hub == null || !adminService.isAdmin(playerUuid)) {
			return;
		}
		if (subscribe) {
			subscribers.computeIfAbsent(hub.getUuid(), uuid -> ConcurrentHashMap.newKeySet()).add(playerUuid);
		} else {
			subscribers.getOrDefault(hub.getUuid(), Set.of()).remove(playerUuid);
		}
	}

	public void onDisconnect(UUID playerUuid) {
		subscribers.values().forEach(set -> set.remove(playerUuid));
	}

	/**
	 * The hub was resized from {@code oldRadius} (same centre): its known blocks move with the zone's corner, those now
	 * outside are dropped; the copies start again ({@code EventsMirrorReset}).
	 */
	public void resize(Hub hub, int oldRadius) {
		Terrain old = terrain(hub, oldRadius);
		Terrain resized = new Terrain(2 * hub.getRadius() + 1);
		int shift = hub.getRadius() - oldRadius;
		synchronized (old) {
			old.tiles.forEach((key, tile) -> {
				int ox = (int) (key >> 32) * TILE;
				int oz = (int) (long) key * TILE;
				for (int y = 0; y < Hub.MIRROR_HEIGHT; y++) {
					for (int z = 0; z < TILE; z++) {
						for (int x = 0; x < TILE; x++) {
							short id = tile.get(x, y, z);
							int nx = ox + x + shift;
							int nz = oz + z + shift;
							if (id != UNKNOWN && old.inside(ox + x, y, oz + z) && resized.inside(nx, y, nz)) {
								resized.set(nx, y, nz, resized.id(old.palette.get(id)));
							}
						}
					}
				}
			});
			resized.tiles.values().forEach(Tile::compact);
			resized.version = old.version + 1;
			resized.dirty = true;
		}
		terrains.put(hub.getUuid(), resized);
		publish(hub, WsMessage.of("EventsMirrorReset", Map.of("hub", hub.getName())));
	}

	// ---------------------------------------------------------------- files

	@Scheduled(fixedRate = 10_000)
	public void saveDirty() {
		terrains.forEach((uuid, terrain) -> hubRepository.findById(uuid).ifPresent(hub -> {
			byte[] bytes;
			synchronized (terrain) {
				if (!terrain.dirty) {
					return;
				}
				terrain.dirty = false;
				bytes = write(terrain);
			}
			try {
				Path folder = root.resolve("hub_" + hub.getName());
				Files.createDirectories(folder);
				Path temp = folder.resolve("mirror.bin.tmp");
				Files.write(temp, bytes);
				Files.move(temp, folder.resolve("mirror.bin"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ex) {
				log.warn("Could not save the terrain of mirror hub {}", hub.getName(), ex);
			}
		}));
	}

	private Hub mirrorHub(String name) {
		return hubRepository.findByNameIgnoreCase(name == null ? "" : name.strip()).filter(Hub::isMirror).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_HUB_NOT_FOUND", Map.of("hub", String.valueOf(name))));
	}

	private Terrain terrain(Hub hub) {
		return terrain(hub, hub.getRadius());
	}

	/** The hub's terrain, loaded for {@code radius} if not in memory yet. */
	private Terrain terrain(Hub hub, int radius) {
		return terrains.computeIfAbsent(hub.getUuid(), uuid -> {
			Terrain terrain = new Terrain(2 * radius + 1);
			Path file = root.resolve("hub_" + hub.getName()).resolve("mirror.bin");
			if (Files.isRegularFile(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					if (!read(terrain, in)) {
						terrain = new Terrain(2 * radius + 1);
					}
				} catch (IOException | RuntimeException ex) {
					log.warn("Could not read the terrain of mirror hub {}, starting empty", hub.getName(), ex);
					terrain = new Terrain(2 * radius + 1);
				}
			}
			return terrain;
		});
	}

	private static byte[] write(Terrain terrain) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
			out.writeInt(FILE_FORMAT);
			out.writeInt(terrain.side);
			out.writeLong(terrain.version);
			out.writeInt(terrain.palette.size());
			for (String state : terrain.palette) {
				out.writeUTF(state);
			}
			out.writeInt(terrain.tiles.size());
			for (Map.Entry<Long, Tile> entry : terrain.tiles.entrySet()) {
				out.writeLong(entry.getKey());
				for (short[] section : entry.getValue().sections) {
					out.writeShort(section == null ? 0 : section.length);
					if (section != null) {
						for (short id : section) {
							out.writeShort(id);
						}
					}
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return bytes.toByteArray();
	}

	/** False if the file is of an older format or another size: the terrain starts empty. */
	private static boolean read(Terrain terrain, InputStream raw) throws IOException {
		DataInputStream in = new DataInputStream(new GZIPInputStream(raw));
		if (in.readInt() != FILE_FORMAT || in.readInt() != terrain.side) {
			return false;
		}
		terrain.version = in.readLong();
		int paletteSize = in.readInt();
		for (int i = 0; i < paletteSize; i++) {
			terrain.id(in.readUTF());
		}
		int tiles = in.readInt();
		for (int t = 0; t < tiles; t++) {
			Tile tile = new Tile();
			terrain.tiles.put(in.readLong(), tile);
			for (int s = 0; s < SECTIONS; s++) {
				int length = in.readUnsignedShort();
				if (length > 0) {
					short[] section = new short[length];
					for (int i = 0; i < length; i++) {
						section[i] = in.readShort();
					}
					tile.sections[s] = section;
				}
			}
		}
		return true;
	}
}
