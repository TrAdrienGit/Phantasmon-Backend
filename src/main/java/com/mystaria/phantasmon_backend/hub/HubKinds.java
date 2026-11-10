package com.mystaria.phantasmon_backend.hub;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mystaria.phantasmon_backend.addon.HubKind;
import com.mystaria.phantasmon_backend.common.ApiException;

/** The hub kinds add-ons bring (TODO-35, D-41): none, and every hub is {@link Hub#KIND_NORMAL}. */
@Component
public class HubKinds {

	private final ObjectProvider<HubKind> kinds;

	public HubKinds(ObjectProvider<HubKind> kinds) {
		this.kinds = kinds;
	}

	/** The kind of that name; empty if no add-on brings it. */
	public Optional<HubKind> find(String kind) {
		return kinds.orderedStream().filter(candidate -> candidate.kind().equals(kind)).findFirst();
	}

	/** The kind of {@code hub} (not {@link Hub#KIND_NORMAL}); {@code ERROR_HUB_KIND_UNAVAILABLE} if its add-on is missing. */
	public HubKind require(Hub hub) {
		return find(hub.getKind()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_KIND_UNAVAILABLE",
				Map.of("hub", hub.getName(), "kind", hub.getKind())));
	}

	/** The add-on kind an admin's {@code POST /admin/hubs} body asks for; empty for an ordinary hub. */
	public Optional<HubKind> requested(Map<String, Object> body) {
		return kinds.orderedStream().filter(candidate -> candidate.requested(body)).findFirst();
	}

	/** The fields {@code hub}'s kind adds to its views; none for an ordinary hub or a kind whose add-on is missing. */
	public Map<String, Object> view(Hub hub) {
		if (hub.isNormal()) {
			return Map.of();
		}
		return find(hub.getKind()).map(kind -> kind.view(hub)).orElse(Map.of());
	}
}
