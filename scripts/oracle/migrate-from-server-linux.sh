#!/usr/bin/env bash
# Moves the live backend from server-linux to the Oracle VM, from the dev machine (Git Bash):
#   scripts/oracle/migrate-from-server-linux.sh ubuntu@<oracle address> [--yes]
# Run scripts/oracle/prepare-vm.sh on the VM first (once). Steps:
#   1. stops the backend on server-linux, so nothing is written after the copy;
#   2. dumps its database (pg_dump -Fc) and packs its data: hub_schematics/ (builds, mirror hubs' mirror.bin, betrayal
#      game rooms and exports), admins.txt, showdown-data/, backups/;
#   3. copies the data to the VM, then deploys there with that dump (scripts/deploy-ubuntu.sh --restore-dump);
#   4. checks /health on the VM. server-linux's backend stays stopped and disabled: to go back, start it again there
#      (systemctl enable --now phantasmon-backend), nothing was deleted.
# Source: PHANTASMON_OLD_HOST (default root@100.101.1.79). The dump and the archive hold real player data: they only
# go through a private temporary folder, deleted at the end.
set -euo pipefail

TO="${1:?Usage: migrate-from-server-linux.sh ubuntu@<oracle address> [--yes]}"
ASSUME_YES=0
[[ "${2:-}" == "--yes" ]] && ASSUME_YES=1
FROM="${PHANTASMON_OLD_HOST:-root@100.101.1.79}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP=/opt/phantasmon

log() { echo "==> $*"; }

ssh -n -o BatchMode=yes -o ConnectTimeout=10 "$FROM" true || { echo "Cannot SSH to $FROM (Tailscale up?)."; exit 1; }
ssh -n -o BatchMode=yes -o ConnectTimeout=10 "$TO" true || { echo "Cannot SSH to $TO."; exit 1; }
ssh -n "$TO" 'test -f /etc/phantasmon/public-proxy' || { echo "$TO is not prepared: run scripts/oracle/prepare-vm.sh there first."; exit 1; }

if ((!ASSUME_YES)); then
	echo "The backend on $FROM will be STOPPED, and $TO's database replaced by a copy of it."
	read -r -p "Type 'yes' to continue: " answer
	[[ "$answer" == "yes" ]] || { echo "Aborted."; exit 1; }
fi

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

log "Stopping the backend on $FROM..."
ssh -n "$FROM" "systemctl stop phantasmon-backend && systemctl disable phantasmon-backend >/dev/null"

DB_NAME="$(grep -E '^BDD_NAME=' "$ROOT/.env" | cut -d= -f2- || true)"
DB_NAME="${DB_NAME:-db_phantasmon}"
log "Dumping database $DB_NAME on $FROM..."
ssh -n "$FROM" "cd /tmp && sudo -u postgres pg_dump -Fc '$DB_NAME'" > "$WORK/server-linux.dump"
[[ -s "$WORK/server-linux.dump" ]] || { echo "Empty dump."; exit 1; }

log "Packing the backend's data on $FROM..."
ssh -n "$FROM" "cd $APP && tar -cf - --ignore-failed-read hub_schematics admins.txt showdown-data backups" > "$WORK/data.tar"

log "Copying the data to $TO..."
REMOTE_HOME="$(ssh -n "$TO" 'echo $HOME')"
SUDO="$(ssh -n "$TO" '[ "$(id -u)" = 0 ] || echo sudo')"
scp -q "$WORK/data.tar" "$TO:$REMOTE_HOME/phantasmon-data.tar"
ssh -n "$TO" "$SUDO mkdir -p $APP && $SUDO tar -xf '$REMOTE_HOME/phantasmon-data.tar' -C $APP && rm -f '$REMOTE_HOME/phantasmon-data.tar'"

log "Deploying on $TO with server-linux's database..."
PHANTASMON_DEPLOY_HOST="$TO" "$ROOT/scripts/deploy-ubuntu.sh" --restore-dump "$WORK/server-linux.dump" --yes

log "Checking the backend on $TO..."
ssh -n "$TO" "curl -fsS http://127.0.0.1:8080/health" && echo
DOMAIN="$(ssh -n "$TO" "cat /etc/phantasmon/public-proxy" | awk '{print $2}' | tr -d '()' || true)"
log "Done. New address for the clients and the mirror server: ${DOMAIN:-https://<domain of the VM>}"
log "server-linux's backend is stopped and disabled (Tailscale Funnel still on there: tailscale funnel reset to turn it off)."
