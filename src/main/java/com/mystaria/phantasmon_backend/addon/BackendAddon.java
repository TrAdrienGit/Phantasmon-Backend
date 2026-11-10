package com.mystaria.phantasmon_backend.addon;

/**
 * A backend add-on loaded from the {@code addons/} folder (D-41), announced by {@code GET /version} ({@code addons}) so
 * its client mod can tell the player when the server lacks it.
 */
public interface BackendAddon {

	/** Its id, the same as its client mod's ({@code phantasmon_events}). */
	String id();

	String version();
}
