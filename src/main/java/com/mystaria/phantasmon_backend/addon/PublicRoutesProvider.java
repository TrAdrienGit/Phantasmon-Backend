package com.mystaria.phantasmon_backend.addon;

import java.util.List;

import org.springframework.http.HttpMethod;

/**
 * Routes of an add-on reachable without a JWT (TODO-35): a public page, or routes checked by a token of their own.
 * Everything else an add-on serves needs a player's token, like the core's routes.
 */
public interface PublicRoutesProvider {

	List<PublicRoute> publicRoutes();

	/** A method and Spring path patterns ({@code /t/*}, {@code /server/mirror/**}). */
	record PublicRoute(HttpMethod method, String... patterns) {
	}
}
