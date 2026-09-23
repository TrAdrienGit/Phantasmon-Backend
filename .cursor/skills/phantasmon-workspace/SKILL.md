---
name: phantasmon-workspace
description: >-
  Guardrails and documentation map for the Phantasmon multi-repo workspace.
  Use at the start of any Phantasmon task, when choosing which repo to edit,
  when consulting CAD/docs, or when the user mentions Ghost Pokémon, Cobblemon
  addon, or the other workspace repositories.
---

# Phantasmon — workspace

Read this skill before writing code. The full spec lives in each Phantasmon repo under `Documentation/`.

## Writable vs read-only

**May write code, config, docs, skills, CI:**

- `Phantasmon-Client`
- `Phantasmon-Backend`

**Read-only documentation.** Never edit, commit, format, or generate files here unless the user explicitly names that repo and asks for a change:

- `architectury-api`
- `ClothConfig`
- `cobblemon`
- `fabric`
- `fabric-language-kotlin`
- `pokemon-showdown`
- `pokemon-showdown-client`

If a fix seems to belong in a reference repo, stop and tell Adrien. Do not patch upstream in this workspace.

## What Phantasmon is

Client-only Fabric mod (Minecraft 1.21.1 + Cobblemon 1.8.1) plus an independent Spring Boot backend. Players create, edit, trade, and battle custom **ghost Pokémon**. The Minecraft/Cobblemon **server never receives Phantasmon code**. Clients talk to the backend over REST + WebSocket.

Old CAD Partie 1 diagrams that show a "Ghost Server Addon" are **obsolete**. Partie 2 is the architecture of record.

## Spec hierarchy (conflicts)

When documents disagree, use this order:

1. `Documentation/CONTEXT_CURSOR_CLIENT.md` or `CONTEXT_CURSOR_BACKEND.md` (agent-facing rules)
2. `Documentation/CAD_Phantasmon_Partie_4_Plan_Developpement.md` (sequence of work)
3. `Documentation/CAD_Ghost_Pokemon_Partie_3_Complements.md`
4. `Documentation/CAD_Ghost_Pokemon_Partie_2_Architecture_Technique.md`
5. `Documentation/PHANTASMON_DB_SCHEMA.md` for SQL (it resolves CAD SQL gaps)
6. `Documentation/phantasmon-backend-openapi.yaml` for HTTP contracts
7. `Documentation/CAD_Ghost_Pokemon_Partie_1.md` for **functional** product (UI, PC, nickname, `[Ghost]` label) — not for server architecture

Known conflict: Partie 1 §7.1 allows illegal movesets; Partie 3 §B and CONTEXT require backend `PokemonLegalityService` (IVs, EVs, moveset, ability). **Follow Partie 3.**

## Non-negotiable architecture

1. No Minecraft server mod, no real Minecraft entity for a ghost Pokémon, no vanilla entity packets.
2. Clients never talk peer-to-peer; everything goes through the backend.
3. Backend is the only source of truth (ownership, legality, trade atomicity, battle session records).
4. Backend stores **identifiers only** (species, form, ability, moves, held item) — never Cobblemon base stats, models, or animations.
5. Ghost gameplay is closed: no XP, auto-evo, breeding, or battles vs real wild Cobblemon. OP spawn of a real Cobblemon from a ghost is a **manual admin procedure**, not API.
6. Hybrid SQL + JSONB. Flyway only, never Hibernate `ddl-auto: update`.
7. Structured errors: `{ "error_code": "ERROR_...", "details": {} }` — never raw user-facing French/English from the API.
8. i18n FR+EN on the client from the first screen. No hardcoded UI strings.
9. Strict TDD on backend (and on client business logic that is not Minecraft rendering). Integration tests use Testcontainers PostgreSQL, never H2.
10. Presence is **in-memory**, not a SQL table. No Redis/multi-instance. No positional anti-cheat. No creation quotas.

## Delivery sequence

Backend first, combat last. Current skeletons are **Phase 0**. Client mod id is `phantasmon` (display name **Phantasmon**), packages `com.mystaria.phantasmon`.

| Phase | Focus |
|-------|--------|
| 0 | Foundations (build, CI, compose, OpenAPI, rename leftover template ids) |
| 1 | Backend identity + Pokémon CRUD + legality + idempotency |
| 2 | Presence + WebSocket + `/version` |
| 3 | Trades |
| 4 | Battle **structure** (not host arbitration) |
| 5 | Client skeleton + Mojang auth + i18n |
| 6 | Client PC / team / editor / Showdown import mapping |
| 7 | Ghost entity render (client-only) |
| 8 | Trade UI |
| 9 | Ghost battle (host client + backend guardrails) |
| 10 | Hardening, backups, Modrinth/CurseForge |

Do not start a later phase unless the user asks or earlier DoD is done. See [doc-index.md](doc-index.md).

## Naming

- Client packages: `com.mystaria.phantasmon`
- Client mod id / display name: `phantasmon` / **Phantasmon**
- Backend packages: `com.mystaria.phantasmon_backend` (CONTEXT's `com.phantasmon.backend` is outdated)

Do not revive the old Fabric example id `cobblemon-addon-ghost-pvp-client`.

Spring Boot in the generated backend is **4.x**, while CONTEXT still says 3.x. Stay on the generated 4.x stack unless Adrien asks to downgrade.

## How to use reference repos

Read APIs, events, and identifiers. Copy patterns, do not vendor their source into Phantasmon.

- Cobblemon species/moves/abilities/render: `cobblemon`
- Fabric loader/client lifecycle: `fabric`
- Kotlin interop only if we add Kotlin (V1 is Java): `fabric-language-kotlin`
- Showdown import format: `pokemon-showdown` + `pokemon-showdown-client`
- Architectury / ClothConfig: only if we later add optional UI/config libs

For Cobblemon identifier notes already extracted, see `Documentation/notes-cobblemon-custom-pokemon.md`.
