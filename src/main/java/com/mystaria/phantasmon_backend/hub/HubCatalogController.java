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

	public HubCatalogController(HubCatalogService catalog, AdminService adminService) {
		this.catalog = catalog;
		this.adminService = adminService;
	}

	/** {@code length} along the anchor's front (z), {@code width} across it (x), {@code height} (y). */
	public record HubCreateRequest(String name, Integer length, Integer width, Integer height) {
	}

	@GetMapping("/hubs")
	public List<HubCatalogService.HubView> list() {
		return catalog.list();
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

	@PostMapping("/admin/hubs")
	public ResponseEntity<HubCatalogService.HubView> create(@RequestBody(required = false) HubCreateRequest request,
			Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		HubCreateRequest body = request == null ? new HubCreateRequest(null, null, null, null) : request;
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(catalog.create(admin, body.name(), body.length(), body.width(), body.height()));
	}

	@DeleteMapping("/admin/hubs/{name}")
	public Map<String, Object> delete(@PathVariable String name, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return catalog.delete(admin, name);
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
