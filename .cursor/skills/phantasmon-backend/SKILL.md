---
name: phantasmon-backend
description: >-
  Implements the Phantasmon Spring Boot backend (Java 21, PostgreSQL, REST, WebSocket).
  Use when editing Phantasmon-Backend, Flyway, auth, Pokémon legality, trades, battles,
  presence, JWT, OpenAPI, or Testcontainers tests.
---

# Phantasmon Backend

Repo: `Phantasmon-Backend`. Independent Spring Boot service — **no Minecraft/Fabric dependency**.

Also load `phantasmon-workspace` for CAD hierarchy and write boundaries.

## Hard rules

1. Never trust the client. Re-check ownership in DB on mutate/delete/trade/battle/clone.
2. Call `PokemonLegalityService` explicitly on create, patch, and Showdown import — not only `@Valid`.
   - IVs each ∈ [0, 31]
   - EVs each ∈ [0, 252], sum ≤ 510
   - moveset and ability consistent with species/form (identifier checks; no duplicated Cobblemon stat tables)
3. Sensitive POSTs (`/pokemon`, `/trades`, `/battles`) take `request_uuid` and use `idempotency_keys`. Replay returns the stored snapshot; never re-execute.
4. Errors: `error_code` + `details` only.
5. Trades: one transaction swaps both `owner_uuid` or neither. No auto-timeout; cancel is explicit.
6. Persist identifiers only in columns + `pokemon.data` JSONB. No base stats / render blobs.
7. `PlayerPresence` is memory + `@Scheduled` TTL cleanup. **No presence table. No Redis.**
8. Do not implement a full Pokémon damage engine (V1: store sessions + result guardrails). Host client is battle authority (Phase 9).
9. No position plausibility checks, no creation quotas, no Minecraft server integration.
10. TDD: write the failing test first. Testcontainers PostgreSQL, never H2.
11. Schema: Flyway `src/main/resources/db/migration/V{n}__description.sql`. Never edit an applied migration. Never `ddl-auto: update`.
12. `/admin/*` requires an admin JWT role. No elevation from gameplay.

## Package layout (by domain)

Keep the **existing** root `com.mystaria.phantasmon_backend` (CONTEXT's `com.phantasmon.backend` is outdated).

```text
auth/  pokemon/  trade/  battle/  presence/  websocket/  version/  admin/  common/
```

Each domain owns its controller, service, repository, dto, entity. No global `controller/` dumping every route.

Spring Boot in this repo is **4.x** (generated). Do not "fix" it down to 3.x unless asked. Add Flyway + docker-compose when doing Phase 0 if they are still missing.

## SQL source of truth

Implement `Documentation/PHANTASMON_DB_SCHEMA.md`, not the partial CREATE TABLE snippets in CAD Partie 2.

V1 tables only: `players`, `pokemon`, `trades`, `battle_sessions`, `idempotency_keys`.

`players.uuid` is the Mojang UUID (not generated here). Pokémon UUID is stable across trades.

JSONB `data` includes nickname, gender, tera_type, ivs, evs, moves, held_item, friendship, origin (schema §4.4).

## Auth

Mojang `joinServer` (client) / `hasJoined` (backend) → short JWT + refresh. `GET /version` is public and used before auth.

Do not invent a shared `server_id` secret between Minecraft server and backend — that model was dropped.

## REST (implement from OpenAPI)

Canonical file: `Documentation/phantasmon-backend-openapi.yaml`.

Public without JWT: `GET /health`, `GET /version`, `POST /auth/session`.

Idempotency header/field on create endpoints as specified in OpenAPI/CONTEXT.

## WebSocket

`/ws?token={jwt}`

Relay spawn/move/despawn only within `server_fingerprint + dimension`. On heartbeat TTL expiry (~30s, sweep ~10s): despawn active ghosts, drop presence.

Do not validate movement physics.

## Tests

- Unit: legality, idempotency, trade atomicity, battle result guardrails.
- Integration: real PostgreSQL JSONB, Flyway, unique PC/team slot indexes.
- Write the test before production code for each feature.

## Phase order on this repo

0 foundations (compose, Flyway, CI, empty domain packages) → 1 identity/Pokémon → 2 WS/presence/version → 3 trades → 4 battle session + guardrails. Client-dependent battle host logic waits for Phase 9.
