# Phantasmon Backend

> Standalone Spring Boot service and **single source of truth** for Phantasmon: player identity, Ghost Pokémon, trades and battle sessions, served to the [Phantasmon Client](https://github.com/TrAdrienGit/Phantasmon-Client) over REST + WebSocket.

## Overview

The backend has no dependency on Minecraft or any game server — it only talks to the client mod. Every sensitive rule is enforced here, never trusted from the client: ownership (re-checked on every mutation), Pokémon legality, idempotent creation, atomic trades, battle-result guardrails. It stores Cobblemon **identifiers only** (species, moves, ability, item…); stats, models and animations are resolved by each client from its own Cobblemon install.

Design documents and reference docs live in [`Documentation/`](./Documentation).

## What it does

| Domain | Highlights |
|---|---|
| **Auth** | `POST /auth/session`: the client proves its Mojang session (`joinServer`), the backend checks it with Mojang's `hasJoined` and issues a short-lived JWT + refresh token (`POST /auth/refresh`, rotated). |
| **Pokémon** | Create (idempotent, auto PC slot), list, PC box view, edit, clone, delete. Legality (IVs 0-31, EVs 0-252, total ≤ 510). Team (6 slots) and PC (16 × 30) with uniform move-or-swap semantics. |
| **Presence & Ghosts** | WebSocket `/ws?token=…`: players grouped by server fingerprint + dimension, heartbeat with TTL cleanup, Ghost spawn/move/despawn relayed to the group (owner included). In memory only. |
| **Live trades** | Invitation → both players see each other's team → offers → both ready → one transaction swaps owners and team slots, recorded in `trades`. In-memory negotiation over the WebSocket. |
| **Async trades** | `POST /trades`, accept/cancel, history — offer now, accept later. |
| **Battles** | Battle sessions and result guardrails (the battle itself will run on a host client — Phase 9). |
| **Version** | `GET /version` handshake (current / minimum supported client version). |
| **Ops** | `GET /health` (DB check), one log file per run under `log/` with 5 GiB retention. |

Every error is structured — `{"error_code": "ERROR_...", "details": {...}}` — and translated by the client. Exact endpoints, WebSocket messages and example payloads: [`Documentation/PHANTASMON_API_REFERENCE.md`](./Documentation/PHANTASMON_API_REFERENCE.md).

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
- PostgreSQL (a local install works; the app creates its schema through Flyway)
- Docker, only to run the test suite (Testcontainers)

## Configuration

Read from environment variables, or from a `.env` file at the project root in development (copy `.env.template`; never commit `.env`):

| Variable | Purpose |
|---|---|
| `BDD_HOST`, `BDD_PORT`, `BDD_NAME`, `BDD_USER`, `BDD_PASSWORD` | PostgreSQL connection |
| `JWT_SECRET` | HMAC key for access/refresh tokens |
| `LOGGING_ENABLED` | Per-run log files under `log/` (default `true`) |

Other tunables (token lifetimes, presence TTL, trade invitation TTL, versions) are in `src/main/resources/application.properties`.

## Run

```bash
./gradlew bootRun                       # development
./gradlew bootJar                       # packaged jar in build/libs/
java -jar build/libs/phantasmon-backend-0.0.1-SNAPSHOT.jar
curl http://localhost:8080/health       # {"status":"UP","database":"UP"}
```

The service listens on port 8080. Full launch guide and troubleshooting: [`Documentation/PHANTASMON_BACKEND_RUNNING.md`](./Documentation/PHANTASMON_BACKEND_RUNNING.md).

## Project structure

```text
src/main/java/com/mystaria/phantasmon_backend/
├── auth/        # Mojang verification, JWT, Spring Security
├── player/      # player identity (Mojang UUID, last username)
├── pokemon/     # CRUD, legality, team/PC slots
├── trade/       # async trades + live trade sessions
├── battle/      # battle sessions, result guardrails
├── presence/    # in-memory presence, grouping, TTL
├── websocket/   # handshake auth, message dispatch, session registry
├── version/     # client version handshake
├── health/      # GET /health
├── logging/     # per-run log files, retention, request logging
├── common/      # structured errors, idempotency, clock
└── config/      # .env loading
src/main/resources/db/migration/   # Flyway V1…V7
```

Database schema and its ER diagram: [`Documentation/PHANTASMON_DB_SCHEMA.md`](./Documentation/PHANTASMON_DB_SCHEMA.md).

## Tests

```bash
./gradlew test
```

110+ tests, written test-first. Integration tests boot the real application against a PostgreSQL Testcontainer (JSONB behaves differently from H2), and WebSocket features are tested with a real embedded server and a real WebSocket client.

## License

CC0 1.0 Universal — see [`LICENSE`](./LICENSE).
