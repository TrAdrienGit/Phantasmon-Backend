#!/usr/bin/env bash
# Runs ON the Ubuntu server, as root, sent by scripts/deploy-ubuntu.sh. Idempotent: every deployment runs it again.
#   setup-server.sh <staging dir> [--restore <dump file>]
# The staging dir holds: phantasmon-backend.jar, env (the .env), db.env (BDD_USER/BDD_PASSWORD/BDD_NAME),
# phantasmon-backend.service, hub_schematics.tar, optionally admins.txt.
set -euo pipefail

STAGING="$1"
shift
RESTORE=""
if [[ "${1:-}" == "--restore" ]]; then
	RESTORE="$2"
fi

APP=/opt/phantasmon
SERVICE=phantasmon-backend

log() { echo "==> [server] $*"; }

# ---------------------------------------------------------------- packages
need=()
command -v java >/dev/null 2>&1 || need+=(openjdk-21-jre-headless)
dpkg -s postgresql >/dev/null 2>&1 || need+=(postgresql)
command -v curl >/dev/null 2>&1 || need+=(curl)
if ((${#need[@]})); then
	log "Installing ${need[*]}..."
	apt-get update -qq
	DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${need[@]}"
fi
systemctl enable --now postgresql >/dev/null

# ---------------------------------------------------------------- user and folders
if ! id phantasmon >/dev/null 2>&1; then
	log "Creating the system user 'phantasmon'..."
	useradd --system --home-dir "$APP" --shell /usr/sbin/nologin phantasmon
fi
mkdir -p "$APP/hub_schematics" "$APP/log" "$APP/backups"

# ---------------------------------------------------------------- database (role + base, password from the .env)
set -a
# shellcheck disable=SC1090
source "$STAGING/db.env"
set +a
BDD_NAME="${BDD_NAME:-db_phantasmon}"
log "PostgreSQL role '$BDD_USER' and database '$BDD_NAME'..."
cd /tmp
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = '$BDD_USER'" | grep -q 1; then
	sudo -u postgres psql -q -c "CREATE ROLE \"$BDD_USER\" LOGIN"
fi
# The password goes through a psql variable on stdin: never on a command line, never logged.
printf "ALTER ROLE \"%s\" WITH LOGIN PASSWORD :'pw';\n" "$BDD_USER" | sudo -u postgres psql -q -v ON_ERROR_STOP=1 -v pw="$BDD_PASSWORD"

stop_service() {
	if systemctl is-active --quiet "$SERVICE"; then
		log "Stopping $SERVICE..."
		systemctl stop "$SERVICE"
	fi
}

if [[ -n "$RESTORE" ]]; then
	stop_service
	log "Restoring the database from the migrated dump (the server's current data is replaced)..."
	sudo -u postgres dropdb --if-exists "$BDD_NAME"
	sudo -u postgres createdb -O "$BDD_USER" "$BDD_NAME"
	# Through stdin: the staging dir (in /root) is not readable by the postgres user.
	sudo -u postgres pg_restore --no-owner --role="$BDD_USER" -d "$BDD_NAME" < "$RESTORE"
	rm -f "$RESTORE"
elif ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$BDD_NAME'" | grep -q 1; then
	sudo -u postgres createdb -O "$BDD_USER" "$BDD_NAME"
fi
unset BDD_PASSWORD

# ---------------------------------------------------------------- application files
stop_service
log "Installing the backend in $APP..."
install -m 644 "$STAGING/phantasmon-backend.jar" "$APP/phantasmon-backend.jar"
install -m 600 "$STAGING/env" "$APP/.env"
tar -xf "$STAGING/hub_schematics.tar" -C "$APP"
if [[ -f "$STAGING/admins.txt" && ! -f "$APP/admins.txt" ]]; then
	# Only the first time: afterwards the server's own admin list is kept.
	install -m 644 "$STAGING/admins.txt" "$APP/admins.txt"
fi
chown -R phantasmon:phantasmon "$APP"

install -m 644 "$STAGING/phantasmon-backend.service" "/etc/systemd/system/$SERVICE.service"

# Daily database backup (TODO-16): pg_dump at 4 am into backups/ (owned by postgres, which dumps).
install -m 755 "$STAGING/backup-database.sh" "$APP/backup-database.sh"
mkdir -p "$APP/backups/daily" "$APP/backups/weekly"
chown -R postgres:postgres "$APP/backups"
chmod 750 "$APP/backups"
install -m 644 "$STAGING/phantasmon-backup.service" /etc/systemd/system/phantasmon-backup.service
install -m 644 "$STAGING/phantasmon-backup.timer" /etc/systemd/system/phantasmon-backup.timer
mkdir -p /etc/systemd/system/phantasmon-backup.service.d
printf '[Service]\nEnvironment=PHANTASMON_DB=%s\n' "$BDD_NAME" > /etc/systemd/system/phantasmon-backup.service.d/database.conf

systemctl daemon-reload
systemctl enable --now phantasmon-backup.timer >/dev/null
systemctl enable "$SERVICE" >/dev/null
log "Starting $SERVICE..."
systemctl start "$SERVICE"

# ---------------------------------------------------------------- health
for _ in $(seq 1 60); do
	if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
		log "Backend UP: $(curl -fsS http://127.0.0.1:8080/health)"
		break
	fi
	sleep 2
done
if ! curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
	log "The backend did not answer /health within 2 minutes — last log lines:"
	journalctl -u "$SERVICE" -n 40 --no-pager
	exit 1
fi

# ---------------------------------------------------------------- Tailscale Funnel (public HTTPS)
if command -v tailscale >/dev/null 2>&1; then
	# Not yet allowed on the tailnet, it waits for the approval link it prints: 3 minutes at most.
	if timeout 180 tailscale funnel --bg 8080 >/tmp/phantasmon-funnel.log 2>&1; then
		log "Tailscale Funnel on: $(tailscale funnel status 2>/dev/null | grep -o 'https://[^ ]*' | head -1)"
	else
		log "Tailscale Funnel could not be turned on (to be allowed in the Tailscale admin console):"
		cat /tmp/phantasmon-funnel.log
	fi
fi
rm -rf "$STAGING"
