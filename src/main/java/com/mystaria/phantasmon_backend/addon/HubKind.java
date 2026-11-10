package com.mystaria.phantasmon_backend.addon;

import java.util.Map;
import java.util.UUID;

import com.mystaria.phantasmon_backend.hub.Hub;
import com.mystaria.phantasmon_backend.hub.HubAnchor;
import com.mystaria.phantasmon_backend.hub.HubAnchorCreateRequest;

/**
 * A kind of hub an add-on defines (TODO-35, D-41; Phantasmon Events' {@code MIRROR} hubs, D-38). The core knows only
 * {@link Hub#KIND_NORMAL} hubs: anyone's anchor, one per player, a box that never overlaps another. A hub of another
 * kind leaves its sizes, its anchors' roles and placement, and what its views show to its kind.
 */
public interface HubKind {

	/** Its name in {@code hubs.kind}: upper case, at most 16 characters, never {@code NORMAL}. */
	String kind();

	/** Whether an admin's {@code POST /admin/hubs} body asks for a hub of this kind (for instance {@code mirror: true}). */
	boolean requested(Map<String, Object> body);

	/** The new hub's size ({@code x} wide, {@code y} high, {@code z} long) from that body; throws its own errors. */
	int[] size(Map<String, Object> body);

	/**
	 * The role of {@code owner}'s new anchor in {@code hub}, from the requested one (null: none asked); throws when the
	 * owner may not have it (not an admin, the hub's quota...). The core then checks the name is free on that server.
	 */
	String anchorRole(UUID ownerUuid, Hub hub, String requestedRole);

	/** The anchor to save, placed as this kind wants it (overlaps are this kind's business). */
	HubAnchor anchor(UUID ownerUuid, Hub hub, HubAnchorCreateRequest request, String role);

	/** Fields added to the hub's and its anchors' views ({@code GET /hubs}, {@code /hub/anchors}), next to the core's. */
	Map<String, Object> view(Hub hub);
}
