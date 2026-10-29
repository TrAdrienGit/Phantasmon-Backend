#!/usr/bin/env bash
# Daily PostgreSQL backup on the Ubuntu server (TODO-16), run by phantasmon-backup.timer as the postgres user.
# Same rules as scripts/backup-database.ps1: custom-format dump (pg_restore) in backups/daily, kept 14 days;
# Sunday's also copied to backups/weekly, kept 92 days. The dumps hold real player data.
set -euo pipefail

APP=/opt/phantasmon
DAILY="$APP/backups/daily"
WEEKLY="$APP/backups/weekly"
# Set by setup-server.sh in the service's drop-in (the .env is readable by the phantasmon user only).
DB="${PHANTASMON_DB:-db_phantasmon}"

mkdir -p "$DAILY" "$WEEKLY"
name="phantasmon_$(date +%Y-%m-%d_%H-%M-%S).dump"
partial="$DAILY/$name.partial"
pg_dump -Fc -d "$DB" -f "$partial"
mv "$partial" "$DAILY/$name"
echo "Backup written: $DAILY/$name ($(du -h "$DAILY/$name" | cut -f1))"

if [[ "$(date +%u)" == "7" ]]; then
	cp "$DAILY/$name" "$WEEKLY/$name"
	echo "Sunday: copied to weekly/"
fi

find "$DAILY" -name 'phantasmon_*.dump' -mtime +14 -print -delete
find "$WEEKLY" -name 'phantasmon_*.dump' -mtime +92 -print -delete
