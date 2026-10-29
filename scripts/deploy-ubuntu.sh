#!/usr/bin/env bash
# Deploys the backend to the Ubuntu server over SSH (Tailscale), from the dev machine (Git Bash):
#   scripts/deploy-ubuntu.sh               build, upload, install / update, (re)start
#   scripts/deploy-ubuntu.sh --migrate-db  same, and first replace the server's database with a fresh dump of the
#                                          local one (scripts/backup-database.ps1) — asks for confirmation
#   --yes                                  no confirmation (with --migrate-db)
# Target: PHANTASMON_DEPLOY_HOST (default root@100.101.1.79, the "server-linux" Tailscale node).
# What it installs and how to run it: Documentation/guides/deploying-ubuntu.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${PHANTASMON_DEPLOY_HOST:-root@100.101.1.79}"
MIGRATE=0
ASSUME_YES=0
for arg in "$@"; do
	case "$arg" in
		--migrate-db) MIGRATE=1 ;;
		--yes) ASSUME_YES=1 ;;
		*) echo "Unknown option: $arg"; exit 1 ;;
	esac
done

log() { echo "==> $*"; }
cd "$ROOT"

[[ -f .env ]] || { echo "No .env at $ROOT — copy .env.template and fill it first."; exit 1; }
ssh -n -o BatchMode=yes -o ConnectTimeout=10 "$HOST" true || { echo "Cannot SSH to $HOST (Tailscale up?)."; exit 1; }

if ((MIGRATE && !ASSUME_YES)); then
	echo "The database ON THE SERVER will be replaced by a copy of your local database."
	read -r -p "Type 'yes' to continue: " answer
	[[ "$answer" == "yes" ]] || { echo "Aborted."; exit 1; }
fi

log "Building the backend jar..."
./gradlew -q bootJar
JAR="$(ls -t build/libs/phantasmon-backend-*.jar | grep -v plain | head -1)"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/phantasmon-backend.jar"
cp .env "$STAGE/env"
# Only what the server's setup needs to create the role; never printed.
grep -E '^(BDD_USER|BDD_PASSWORD|BDD_NAME)=' .env > "$STAGE/db.env"
cp scripts/ubuntu/phantasmon-backend.service scripts/ubuntu/setup-server.sh scripts/ubuntu/backup-database.sh scripts/ubuntu/phantasmon-backup.service scripts/ubuntu/phantasmon-backup.timer "$STAGE/"
tar -cf "$STAGE/hub_schematics.tar" hub_schematics
[[ -f admins.txt ]] && cp admins.txt "$STAGE/"

RESTORE_ARGS=()
if ((MIGRATE)); then
	log "Dumping the local database..."
	powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/scripts/backup-database.ps1")"
	DUMP="$( (ls -t backups/daily/phantasmon_*.dump backups/phantasmon_*.dump 2>/dev/null || true) | head -1)"
	[[ -n "$DUMP" ]] || { echo "No dump found in backups/."; exit 1; }
	log "Using $DUMP"
	cp "$DUMP" "$STAGE/migration.dump"
fi

REMOTE_STAGE="/root/phantasmon-deploy-$(date +%s)"
log "Uploading to $HOST..."
ssh -n "$HOST" "mkdir -p '$REMOTE_STAGE' && chmod 700 '$REMOTE_STAGE'"
scp -q "$STAGE"/* "$HOST:$REMOTE_STAGE/"
if ((MIGRATE)); then
	RESTORE_ARGS=(--restore "$REMOTE_STAGE/migration.dump")
fi

log "Installing on the server..."
ssh -n "$HOST" "bash '$REMOTE_STAGE/setup-server.sh' '$REMOTE_STAGE' ${RESTORE_ARGS[*]:-}"
log "Done. Logs: ssh $HOST journalctl -u phantasmon-backend -f"
