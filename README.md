# Phantasmon Backend

> Standalone Spring Boot service and **single source of truth** for Phantasmon: player identity, Ghost Pokémon, trades and battle sessions, served to the [Phantasmon Client](https://github.com/TrAdrienGit/Phantasmon-Client) over REST + WebSocket.

## Overview

The backend has no dependency on Minecraft or any game server — it only talks to the client mod. Every sensitive rule is enforced here, never trusted from the client: ownership (re-checked on every mutation), Pokémon legality, idempotent creation, atomic trades, battle-result guardrails. It stores Cobblemon **identifiers only** (species, moves, ability, item…); stats, models and animations are resolved by each client from its own Cobblemon install.

Full documentation (architecture, API and WebSocket references, database schema, guides, specifications, project status) lives in [`Documentation/`](./Documentation/README.md), in French.

## What it does

| Domain | Highlights |
|---|---|
| **Auth** | `POST /auth/session`: the client proves its Mojang session (`joinServer`), the backend checks it with Mojang's `hasJoined` and issues a short-lived JWT + refresh token (`POST /auth/refresh`, rotated). |
| **Pokémon** | Create (idempotent, auto PC slot), list, PC box view, edit, clone, delete. Legality (IVs 0-31, EVs 0-252, total ≤ 510). Team (6 slots) and PC (16 × 30) with uniform move-or-swap semantics. |
| **Presence & Ghosts** | WebSocket `/ws?token=…`: players grouped by server fingerprint + dimension, heartbeat with TTL cleanup, Ghost spawn/move/despawn relayed to the group (owner included). In memory only. |
| **Live trades** | Invitation → both players see each other's team → offers → both ready → one transaction swaps owners and team slots, recorded in `trades`. In-memory negotiation over the WebSocket. |
| **Async trades** | `POST /trades`, accept/cancel, history — offer now, accept later. |
| **Live battles** | Invitation → the backend picks the **host** (alternating between the same two players) → the host client runs Cobblemon's own battle engine → the backend relays its encoded packets to the guest and the guest's choices back, handles the optional 90 s turn timer and records the result in `battle_sessions`. |
| **Battle sessions (REST)** | `POST /battles`, result submission with guardrails — kept, not used by the live flow. |
| **Version** | `GET /version` handshake (current / minimum supported client version). |
| **Ops** | `GET /health` (DB check), one log file per run under `log/` with 5 GiB retention. |

Every error is structured — `{"error_code": "ERROR_...", "details": {...}}` — and translated by the client. Exact endpoints and payloads: [`rest-api.md`](./Documentation/reference/rest-api.md) ([OpenAPI](./Documentation/reference/openapi.yaml)); WebSocket messages: [`websocket-protocol.md`](./Documentation/reference/websocket-protocol.md); error codes: [`error-codes.md`](./Documentation/reference/error-codes.md).

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1 (Web MVC, WebSocket, Security, Data JPA, Validation) |
| Database | PostgreSQL — typed columns + JSONB, Flyway migrations |
| Auth | Mojang session verification → JWT (jjwt) |
| Build | Gradle |
| Tests | JUnit 5 + Testcontainers (real PostgreSQL, never H2) |

## Requirements

- JDK 21
- PostgreSQL (a local install, or the provided `docker-compose.yml`; the app creates its schema through Flyway)
- Docker, only to run the test suite (Testcontainers)

## Configuration

Read from environment variables, or from a `.env` file at the project root in development (copy `.env.template`; never commit `.env`):

| Variable | Purpose |
|---|---|
| `BDD_HOST`, `BDD_PORT`, `BDD_NAME`, `BDD_USER`, `BDD_PASSWORD` | PostgreSQL connection |
| `JWT_SECRET` | HMAC key for access/refresh tokens |
| `LOGGING_ENABLED` | Per-run log files under `log/` (default `true`) |
| `BDD_DOCKER_PORT` | Host port of the optional PostgreSQL container (default `5433`, `docker-compose.yml` only) |

Other tunables (token lifetimes, presence TTL, trade invitation TTL, versions) are in `src/main/resources/application.properties`.

## Run

```bash
./gradlew bootRun                       # development
./gradlew bootJar                       # packaged jar in build/libs/
java -jar build/libs/phantasmon-backend-0.0.1-SNAPSHOT.jar
curl http://localhost:8080/health       # {"status":"UP","database":"UP"}
```

The service listens on port 8080. Full launch guide, PostgreSQL in Docker and troubleshooting: [`Documentation/guides/running.md`](./Documentation/guides/running.md). All settings: [`configuration.md`](./Documentation/reference/configuration.md).

## Project structure

```text
src/main/java/com/mystaria/phantasmon_backend/
├── auth/        # Mojang verification, JWT, Spring Security
├── player/      # player identity (Mojang UUID, last username)
├── pokemon/     # CRUD, legality, team/PC slots
├── trade/       # async trades + live trade sessions
├── battle/      # live battles (invite, host, relay, timer) + REST sessions
├── presence/    # in-memory presence, grouping, TTL
├── websocket/   # handshake auth, message dispatch, session registry
├── version/     # client version handshake
├── health/      # GET /health
├── logging/     # per-run log files, retention, request logging
├── common/      # structured errors, idempotency, clock
└── config/      # .env loading
src/main/resources/db/migration/   # Flyway V1…V8
```

Database schema, ER diagram and migration history: [`Documentation/reference/database-schema.md`](./Documentation/reference/database-schema.md).

## Tests

```bash
./gradlew test
```

About 125 tests, written test-first. Integration tests boot the real application against a PostgreSQL Testcontainer (JSONB behaves differently from H2), and WebSocket features are tested with a real embedded server and a real WebSocket client.

## License

GNU General Public License v3.0 — see [`LICENSE`](./LICENSE).
