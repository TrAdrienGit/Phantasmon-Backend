package com.mystaria.phantasmon_backend.hub;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Global Hub's build (D-34): what it is, then the file itself, which clients cache by its SHA-256. */
@RestController
public class HubSchematicController {

	private final HubSchematicService hubSchematicService;

	public HubSchematicController(HubSchematicService hubSchematicService) {
		this.hubSchematicService = hubSchematicService;
	}

	public record HubSchematicResponse(String name, HubSchematicService.Format format, String sha256, Size size, int bytes) {

		public record Size(int x, int y, int z) {
		}
	}

	@GetMapping("/hub/schematic")
	public HubSchematicResponse global() {
		HubSchematicService.Schematic schematic = hubSchematicService.global();
		return new HubSchematicResponse(schematic.fileName(), schematic.format(), schematic.sha256(),
				new HubSchematicResponse.Size(schematic.sizeX(), schematic.sizeY(), schematic.sizeZ()), schematic.bytes().length);
	}

	@GetMapping("/hub/schematic/file")
	public ResponseEntity<byte[]> globalFile() {
		HubSchematicService.Schematic schematic = hubSchematicService.global();
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.eTag("\"" + schematic.sha256() + "\"")
				.cacheControl(CacheControl.noCache())
				.body(schematic.bytes());
	}
}
