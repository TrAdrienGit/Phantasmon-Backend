package com.mystaria.phantasmon_backend.hub;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.admin.AdminService;

/** The hubs (D-35): listed and their builds served to every player; created, deleted and reloaded by admins. */
@RestController
public class HubCatalogController {

	private final HubCatalogService catalog;
	private final AdminService adminService;
	private final HubKinds hubKinds;

	public HubCatalogController(HubCatalogService catalog, AdminService adminService, HubKinds hubKinds) {
		this.catalog = catalog;
		this.adminService = adminService;
		this.hubKinds = hubKinds;
	}

	@GetMapping("/hubs")
	public List<HubCatalogService.HubView> list() {
		return catalog.list();
	}

	/** The hub manager for players: joinable hubs with their size, build, creation date and anchor count. */
	@GetMapping("/hubs/overview")
	public List<HubCatalogService.HubAdminView> overview() {
		return catalog.playerList();
	}

	@GetMapping("/hubs/{name}/schematic/file")
	public ResponseEntity<byte[]> schematicFile(@PathVariable String name) {
		HubCatalogService.Schematic schematic = catalog.schematicOf(name);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.eTag("\"" + schematic.sha256() + "\"")
				.cacheControl(CacheControl.noCache())
				.body(schematic.bytes());
	}

	/**
	 * {@code name}; an ordinary hub: {@code length} along the anchor's front (z), {@code width} across it (x),
	 * {@code height} (y). A body an add-on's hub kind recognizes (D-41; mirror hubs: {@code mirror: true} and
	 * {@code radius}, D-38) creates a hub of that kind, sized by it.
	 */
	@PostMapping("/admin/hubs")
	public ResponseEntity<HubCatalogService.HubView> create(@RequestBody(required = false) Map<String, Object> request,
			Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		Map<String, Object> body = request == null ? Map.of() : request;
		String name = body.get("name") instanceof String text ? text : null;
		return ResponseEntity.status(HttpStatus.CREATED).body(hubKinds.requested(body)
				.map(kind -> catalog.create(admin, name, kind, body))
				.orElseGet(() -> catalog.create(admin, name, integer(body, "length"), integer(body, "width"),
						integer(body, "height"))));
	}

	/** An integer field of a JSON body; null if absent or not a whole number. */
	public static Integer integer(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value instanceof Integer number) {
			return number;
		}
		if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
				&& Math.abs(number.doubleValue()) <= Integer.MAX_VALUE) {
			return number.intValue();
		}
		return null;
	}

	@DeleteMapping("/admin/hubs/{name}")
	public Map<String, Object> delete(@PathVariable String name, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return catalog.delete(admin, name);
	}

	/** The hub manager (admins): every hub with its creation, build, anchor count and availability. */
	@GetMapping("/admin/hubs")
	public List<HubCatalogService.HubAdminView> adminList(Authentication authentication) {
		adminService.requireAdmin(caller(authentication));
		return catalog.adminList();
	}

	/** The hub manager: the anchors leading to a hub, on every server, with their owners. */
	@GetMapping("/admin/hubs/{name}/anchors")
	public List<HubCatalogService.AnchorAdminView> adminAnchors(@PathVariable String name, Authentication authentication) {
		adminService.requireAdmin(caller(authentication));
		return catalog.adminAnchors(name);
	}

	public record HubRenameRequest(String name) {
	}

	@PostMapping("/admin/hubs/{name}/rename")
	public HubCatalogService.HubView rename(@PathVariable String name, @RequestBody(required = false) HubRenameRequest request,
			Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return catalog.rename(admin, name, request == null ? null : request.name());
	}

	/** The hub manager: gives a classic hub its build (the file's bytes as the body, {@code file_name} in the query). */
	@org.springframework.web.bind.annotation.PutMapping(value = "/admin/hubs/{name}/schematic",
			consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public HubCatalogService.HubView uploadSchematic(@PathVariable String name,
			@org.springframework.web.bind.annotation.RequestParam("file_name") String fileName, @RequestBody byte[] body,
			Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return catalog.uploadSchematic(admin, name, fileName, body);
	}

	/** The hub manager: the hub goes without a build. */
	@DeleteMapping("/admin/hubs/{name}/schematic")
	public HubCatalogService.HubView removeSchematic(@PathVariable String name, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return catalog.removeSchematic(admin, name);
	}

	@PostMapping("/admin/hubs/{name}/reload")
	public HubCatalogService.HubView reload(@PathVariable String name, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return catalog.reload(admin, name);
	}

	private static UUID caller(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
