package com.mystaria.phantasmon_backend.hub;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Matches {@code POST /hub/anchors}. {@code hub}: the name of the hub it leads to (D-35). The name is 3 to 32 letters, digits, spaces, {@code -} or {@code _}, neither
 * starting nor ending with a space; fingerprint and dimension follow the presence rules (SEC-4). Coordinates stay
 * inside Minecraft's world limits; any yaw is accepted and snapped to a quarter turn.
 */
public record HubAnchorCreateRequest(
		@NotNull UUID requestUuid,
		@NotBlank @Size(max = 32) String hub,
		@NotNull @Pattern(regexp = "[\\p{L}\\p{N}_-][\\p{L}\\p{N} _-]{1,30}[\\p{L}\\p{N}_-]") String name,
		@NotBlank @Size(max = 128) String serverFingerprint,
		@NotBlank @Size(max = 128) String dimension,
		@NotNull @Valid Origin origin,
		@NotNull Double yaw) {

	public record Origin(
			@NotNull @DecimalMin("-30000000") @DecimalMax("30000000") Double x,
			@NotNull @DecimalMin("-2048") @DecimalMax("2048") Double y,
			@NotNull @DecimalMin("-30000000") @DecimalMax("30000000") Double z) {
	}
}
