/**
 * Extension points for backend add-ons (TODO-35, D-41): the only way an add-on such as Phantasmon Events plugs into
 * the core. The core never imports an add-on's classes; it finds the add-on's beans implementing these interfaces
 * (none: the feature is simply absent).
 *
 * <ul>
 *   <li>{@link com.mystaria.phantasmon_backend.addon.WsMessageHandler}: WebSocket message types of its own;</li>
 *   <li>{@link com.mystaria.phantasmon_backend.addon.NpcOpponentProvider}: trainer NPCs a player can battle;</li>
 *   <li>{@link com.mystaria.phantasmon_backend.addon.TournamentHook}: tournament matches played as Ghost battles;</li>
 *   <li>{@link com.mystaria.phantasmon_backend.addon.HubListener}: hub changes an add-on follows;</li>
 *   <li>{@link com.mystaria.phantasmon_backend.addon.PublicRoutesProvider}: routes reachable without a token.</li>
 * </ul>
 */
package com.mystaria.phantasmon_backend.addon;
