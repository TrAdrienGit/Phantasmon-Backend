# Phantasmon Backend

> Independent Spring Boot service and single source of truth for the Phantasmon system: manages player identity, ghost Pokémon, trades, and battles, and broadcasts them in real time to authorized clients over REST + WebSocket.

## Overview

The Phantasmon Backend does not depend on any Minecraft server — it runs as a standalone service and talks only to the [Phantasmon Client](https://github.com/TrAdrienGit/phantasmon-client) (Fabric mod). It is the sole authority of the system: ownership, Pokémon legality, trade atomicity, and battle arbitration are all enforced here.

See the [full design document](https://github.com/TrAdrienGit/phantasmon-backend/Documentation) for the complete architecture, technical decisions, and development plan.

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Gradle |
| Database | PostgreSQL (columns + JSONB, Flyway migrations) |
| API | REST + WebSocket |
| Auth | Mojang session verification (`joinServer`/`hasJoined`) → JWT |
| Tests | JUnit 5 + Testcontainers |

## Requirements

- JDK 21
- Docker (for local PostgreSQL and Testcontainers-based integration tests)

## Running in development

```bash
git clone https://github.com/your-account/phantasmon-backend.git
cd phantasmon-backend

# Copy the environment template and fill in local PostgreSQL credentials
cp .env.template .env

# Run the backend
./gradlew bootRun
```

The service listens on `http://localhost:8080` by default. The reference OpenAPI spec is included in this repo.

## Build

```bash
./gradlew build
```

## Project structure

```text
phantasmon-backend/
├── src/main/java/com/phantasmon/backend/
│   ├── auth/         # Mojang flow, JWT, Spring Security
│   ├── pokemon/      # CRUD, legality, Showdown import
│   ├── trade/        # Atomic trades
│   ├── battle/       # Battle sessions, guardrails
│   ├── presence/     # In-memory presence, grouping, TTL cleanup
│   ├── websocket/    # WebSocket config and handlers
│   ├── version/      # Client/backend version handshake
│   ├── admin/        # Role-protected admin routes
│   └── common/       # Shared DTOs, error handling, idempotency
├── src/main/resources/
│   └── db/migration/ # Flyway migrations
├── docker-compose.yml
└── build.gradle
```

## Tests

```bash
./gradlew test
```

Integration tests run against a real PostgreSQL instance via Testcontainers (no H2), to properly cover JSONB behavior. Strict TDD: a test is written before each new feature, and the full suite is re-run on every commit.

## License

CC0 1.0 Universal — see [`LICENSE`](./LICENSE).
