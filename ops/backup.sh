#!/bin/sh
# Nightly database backup for docker-compose.prod.yml (runs in a postgres:17 container).
#   /backup.sh        loop: dump every night at 02:00 (TZ), keep KEEP_DAYS days
#   /backup.sh once   dump now and exit (manual backup, or to test the setup)
# Restore: pg_restore --clean --if-exists -d baari /backups/baari-YYYY-MM-DD.dump
set -eu

dump() {
    file="/backups/baari-$(date +%F).dump"
    pg_dump --format=custom --file="$file.tmp" && mv "$file.tmp" "$file"
    find /backups -name 'baari-*.dump' -mtime +"${KEEP_DAYS:-14}" -delete
    echo "backup: wrote $file ($(du -h "$file" | cut -f1))"
}

if [ "${1:-}" = "once" ]; then
    dump
    exit 0
fi

while true; do
    now=$(date +%s)
    next=$(date -d "tomorrow 02:00" +%s)
    today=$(date -d "today 02:00" +%s)
    [ "$today" -gt "$now" ] && next=$today
    echo "backup: next run at $(date -d "@$next")"
    sleep $((next - now))
    dump || echo "backup: FAILED"
done
