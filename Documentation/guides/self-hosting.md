# Hosting your own Phantasmon backend

The guide for a community that wants its own Phantasmon network, linked to the mod pages on Modrinth and CurseForge.
English on purpose: it is read by people outside the project. The project's own deployment (French):
[`deploying-ubuntu.md`](deploying-ubuntu.md).

## What you are setting up

Phantasmon is two parts:

- **The mod** (Phantasmon, a client-side Fabric mod), installed by every player. It never needs a server mod or a
  plugin: it works on any Cobblemon server, and in single player.
- **The backend** (this repository), one program for your whole community. It is the single source of truth: player
  accounts, Ghost Pokémon, PC boxes, trades, battles, hubs, rewards. Every rule is checked here, never trusted from a
  game client.

Every player of your community points their mod at your backend. Players who use different backends never see each
other.

```text
 Player A (mod) ─┐                       ┌─ PostgreSQL
 Player B (mod) ─┼── HTTPS + WebSocket ──┤
 Player C (mod) ─┘                       └─ Phantasmon backend ── addons/ (optional modules, e.g. Phantasmon Events)
```

## 1. Requirements

| What | Version | Notes |
|---|---|---|
| A machine that stays on | Linux, Windows or macOS | A small VPS or a home server. 1 CPU core and 2 GB of RAM are enough for a community. |
| Java | **21** (a JRE is enough to run) | e.g. `openjdk-21-jre-headless` on Ubuntu. Building needs a JDK 21. |
| PostgreSQL | 16 or later (18 recommended) | Native install, or the provided `docker-compose.yml`. |
| A public HTTPS address | | Players connect from their own machines: see §5. |

Players need a **premium (Microsoft) Minecraft account**: they log in to the backend with Mojang's session check, as
a Minecraft server does. Offline accounts are not supported.

## 2. Build the backend

```bash
git clone https://github.com/TrAdrienGit/Phantasmon-Backend.git
cd Phantasmon-Backend
./gradlew bootJar
```

The jar is `build/libs/phantasmon-backend-<version>.jar` (take the one **without** `-plain`). Use the release tag that
matches the mod version your players install (see §8).

## 3. Prepare the database

As a PostgreSQL superuser, create a role and a database it owns:

```sql
CREATE ROLE phantasmon LOGIN PASSWORD '<a strong password>';
CREATE DATABASE db_phantasmon OWNER phantasmon;
```

Nothing else: the backend creates and upgrades its own tables at every start (Flyway migrations).

## 4. Configure and run

Make a folder for the backend, e.g. `/opt/phantasmon/`, and put in it:

| File / folder | What |
|---|---|
| `phantasmon-backend.jar` | The jar you built. |
| `.env` | The settings below (copy `.env.template` from the repository). Keep it private: it holds your database password and your signing key. |
| `admins.txt` | The Minecraft usernames of your admins, one per line. Read again while running: no restart needed. |
| `hub_schematics/` | Your hubs' builds, one `hub_<name>/` folder each (see the documentation). Can stay empty. |
| `addons/` | Optional backend modules (§7). Empty: Phantasmon alone. |

`.env`:

| Variable | Value |
|---|---|
| `BDD_HOST`, `BDD_PORT` | Your PostgreSQL, e.g. `localhost`, `5432` |
| `BDD_NAME`, `BDD_USER`, `BDD_PASSWORD` | The database and role from §3 |
| `JWT_SECRET` | A long random key that signs the players' sessions: `openssl rand -base64 64`. Never share it; changing it logs everyone out. |
| `LOGGING_ENABLED` | `true` writes one log file per run in `log/` (5 GB cap, oldest deleted first) |

Run it **from that folder** (the `.env`, `admins.txt`, `hub_schematics/`, `addons/` and `log/` are found there):

```bash
cd /opt/phantasmon
java -Xmx2g -jar phantasmon-backend.jar
```

It listens on port 8080 (`SERVER_PORT` changes it, `SERVER_ADDRESS=127.0.0.1` keeps it local behind a proxy). Check
it:

```bash
curl http://localhost:8080/health    # {"status":"UP","database":"UP"}
curl http://localhost:8080/version   # the version and the loaded add-ons
```

At each start the backend fetches Pokémon Showdown's battle rules from `play.pokemonshowdown.com` and caches them in
`showdown-data/`. Without internet, it uses the cached copy, then the copy shipped in the jar.

### Run it as a service (Linux)

The repository has a ready systemd unit: [`scripts/ubuntu/phantasmon-backend.service`](../../scripts/ubuntu/phantasmon-backend.service).
It runs the jar as a `phantasmon` system user from `/opt/phantasmon`, restarts it on failure and starts it at boot.
Copy it to `/etc/systemd/system/`, adjust the paths, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now phantasmon-backend
journalctl -u phantasmon-backend -f
```

[`scripts/ubuntu/setup-server.sh`](../../scripts/ubuntu/setup-server.sh) is the script the project uses for its own
server (Java, PostgreSQL, the user, the service, daily backups): read it before you reuse it, it is written for that
server.

## 5. Make it reachable

Players connect to `https://<your address>`, REST and WebSocket (`/ws`) on the same address. Use HTTPS: the players'
session tokens travel in it.

Any of these works:

- **A reverse proxy** with a certificate (Caddy, nginx, Traefik) in front of `127.0.0.1:8080`. It must pass WebSocket
  upgrades. With Caddy, the whole configuration is:

  ```text
  phantasmon.example.org {
      reverse_proxy 127.0.0.1:8080
  }
  ```

- **Tailscale Funnel** (what the project uses): `tailscale funnel --bg 8080` gives a public HTTPS address, with no port
  to open on your router.
- **A tunnel** service (Cloudflare Tunnel or similar), if it supports WebSocket.

## 6. Point the players at it

Each player sets your address once, either:

- in game: the Phantasmon menu (**H**, or Mod Menu → Phantasmon) → **Settings** → backend address, then restart the
  game; or
- in `config/phantasmon.json` of their instance (created at the first launch):

  ```json
  { "backend_url": "https://phantasmon.example.org" }
  ```

If you ship a modpack, include that file in it: your players then have nothing to set.

The mod logs in by itself when the player joins a world or a server. Admins (from `admins.txt`) get the admin
commands and menus in game.

## 7. Add-ons: Phantasmon Events

Backend modules go in `addons/` and are loaded at start. The first one is the backend part of
[Phantasmon Events](https://github.com/TrAdrienGit/Phantasmon-Events) (event NPCs, tournaments, the League's intros,
the betrayal game). Its players also install the Phantasmon Events mod.

Build it against the same backend version:

```bash
cd Phantasmon-Backend && ./gradlew publishToMavenLocal -x test   # the module compiles against this
cd ../Phantasmon-Events/backend && ./gradlew jar
cp build/libs/phantasmon-events-backend-*.jar /opt/phantasmon/addons/
```

Restart the backend: `/version` now lists the module, and its tables are created. To turn it off, remove the jar from
`addons/` and restart; its tables stay, unused.

## 8. Updating

- Mod and backend versions go together: the backend refuses mods older than its minimum version, and tells those
  players to update. Update the backend first, then tell your players to update the mod.
- To update: stop the service, replace the jar (and the add-on jars), start it. Migrations run by themselves.
- **Back up before every update.** A daily `pg_dump` is enough:

  ```bash
  pg_dump -Fc -U phantasmon db_phantasmon > phantasmon-$(date +%F).dump
  ```

  Keep copies on another machine as well.

## 9. Good to know

- Only data goes through the backend. Pokémon models, animations and battles' visuals come from each player's own
  Cobblemon. Battles run on one of the two players' game (alternating), and the backend relays them.
- Positions are only shared between players of the same Minecraft server (a hash of its address) and dimension, while
  they play.
- The documentation of the API, the WebSocket protocol, the database and every setting is in
  [`Documentation/`](../README.md) (French).
- Problems and questions: the repository's issues.
