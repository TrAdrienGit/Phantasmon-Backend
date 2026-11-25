#!/usr/bin/env bash
# Deploys the backend to the Ubuntu server over SSH (Tailscale), from the dev machine (Git Bash):
#   scripts/deploy-ubuntu.sh               build, upload, install / update, (re)start
#   scripts/deploy-ubuntu.sh --migrate-db  same, and first replace the server's database with a fresh dump of the
#                                          local one (scripts/backup-database.ps1) — asks for confirmation
#   --restore-dump <file>                  same as --migrate-db, but with that dump (pg_dump -Fc) instead of the local
#                                          database: scripts/oracle/migrate-from-server-linux.sh passes server-linux's
#   --yes                                  no confirmation (with --migrate-db / --restore-dump)
#   --no-events                            without the Phantasmon Events module (D-41): the server's addons/ is emptied
# When ../Phantasmon-Events/backend exists, its module is always built and kept in the server's addons-available/ (the
# server manager can turn it on later); it is also put in addons/ (enabled) unless --no-events.
# Target: PHANTASMON_DEPLOY_HOST (default root@100.101.1.79, the "server-linux" Tailscale node); for the Oracle VM,
# PHANTASMON_DEPLOY_HOST=ubuntu@<its address> (a user with sudo works too, see Documentation/guides/deploying-oracle.md).
# What it installs and how to run it: Documentation/guides/deploying-ubuntu.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${PHANTASMON_DEPLOY_HOST:-root@100.101.1.79}"
MIGRATE=0
ASSUME_YES=0
EVENTS=1
DUMP_FILE=""
while (($#)); do
	case "$1" in
		--migrate-db) MIGRATE=1 ;;
		--restore-dump) MIGRATE=1; DUMP_FILE="${2:?--restore-dump needs a file}"; shift ;;
		--yes) ASSUME_YES=1 ;;
		--no-events) EVENTS=0 ;;
		*) echo "Unknown option: $1"; exit 1 ;;
	esac
	shift
done

log() { echo "==> $*"; }
cd "$ROOT"

[[ -f .env ]] || { echo "No .env at $ROOT — copy .env.template and fill it first."; exit 1; }
ssh -n -o BatchMode=yes -o ConnectTimeout=10 "$HOST" true || { echo "Cannot SSH to $HOST (Tailscale up?)."; exit 1; }

if ((MIGRATE && !ASSUME_YES)); then
	echo "The database ON THE SERVER ($HOST) will be replaced by ${DUMP_FILE:-a copy of your local database}."
	read -r -p "Type 'yes' to continue: " answer
	[[ "$answer" == "yes" ]] || { echo "Aborted."; exit 1; }
fi

log "Building the backend jar..."
./gradlew -q bootJar publishToMavenLocal
JAR="$(ls -t build/libs/phantasmon-backend-*.jar | grep -v plain | head -1)"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/phantasmon-backend.jar"
EVENTS_DIR="$ROOT/../Phantasmon-Events/backend"
touch "$STAGE/addons-enabled"
if [[ -d "$EVENTS_DIR" ]]; then
	log "Building the Phantasmon Events module..."
	(cd "$EVENTS_DIR" && ./gradlew -q jar)
	cp "$(ls -t "$EVENTS_DIR"/build/libs/phantasmon-events-backend-*.jar | head -1)" "$STAGE/addon-phantasmon-events-backend.jar"
	if ((EVENTS)); then
		echo "phantasmon-events-backend.jar" > "$STAGE/addons-enabled"
	fi
fi
cp .env "$STAGE/env"
# Only what the server's setup needs to create the role; never printed.
grep -E '^(BDD_USER|BDD_PASSWORD|BDD_NAME)=' .env > "$STAGE/db.env"
cp scripts/ubuntu/phantasmon-backend.service scripts/ubuntu/setup-server.sh scripts/ubuntu/backup-database.sh scripts/ubuntu/phantasmon-backup.service scripts/ubuntu/phantasmon-backup.timer "$STAGE/"
tar -cf "$STAGE/hub_schematics.tar" hub_schematics
[[ -f admins.txt ]] && cp admins.txt "$STAGE/"

RESTORE_ARGS=()
if [[ -n "$DUMP_FILE" ]]; then
	[[ -f "$DUMP_FILE" ]] || { echo "No dump at $DUMP_FILE."; exit 1; }
	cp "$DUMP_FILE" "$STAGE/migration.dump"
elif ((MIGRATE)); then
	log "Dumping the local database..."
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/scripts/backup-database.ps1")"
	DUMP="$( (ls -t backups/daily/phantasmon_*.dump backups/phantasmon_*.dump 2>/dev/null || true) | head -1)"
	[[ -n "$DUMP" ]] || { echo "No dump found in backups/."; exit 1; }
	log "Using $DUMP"
	cp "$DUMP" "$STAGE/migration.dump"
fi

# root (server-linux) runs the setup directly; another user (Oracle's 'ubuntu') stages in its home and goes through sudo.
REMOTE_HOME="$(ssh -n "$HOST" 'echo $HOME')"
SUDO="$(ssh -n "$HOST" '[ "$(id -u)" = 0 ] || echo sudo')"
REMOTE_STAGE="$REMOTE_HOME/phantasmon-deploy-$(date +%s)"
log "Uploading to $HOST..."
ssh -n "$HOST" "mkdir -p '$REMOTE_STAGE' && chmod 700 '$REMOTE_STAGE'"
scp -q "$STAGE"/* "$HOST:$REMOTE_STAGE/"
if ((MIGRATE)); then
	RESTORE_ARGS=(--restore "$REMOTE_STAGE/migration.dump")
fi

log "Installing on the server..."
ssh -n "$HOST" "$SUDO bash '$REMOTE_STAGE/setup-server.sh' '$REMOTE_STAGE' ${RESTORE_ARGS[*]:-}"
log "Done. Logs: ssh $HOST $SUDO journalctl -u phantasmon-backend -f"
