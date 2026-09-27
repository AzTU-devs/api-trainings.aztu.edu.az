#!/usr/bin/env bash
# Nightly backup of the trainings platform: the PostgreSQL database and the
# uploaded files in /opt/uploads. The two belong together: the database holds only
# object keys, so a dump restored without the files gives a catalogue of covers and
# lesson videos that all 404.
#
# Installed by DB_SETUP.md §9 as /usr/local/sbin/backup-eduplatform and run from
# /etc/cron.d/eduplatform-backup.
#
#   backup-eduplatform           take a backup, prune old ones, record success
#   backup-eduplatform --check   exit 1 if the last success is older than MAX_AGE_HOURS
#
# It runs as root, not as postgres. It has to read /opt/uploads (owned by the
# container's uid 1001) and write the backup directory, and the procedure this
# replaces failed because postgres could do neither. pg_dump still runs as
# postgres, over the local socket with peer auth, but this script opens the output
# file, so postgres needs no access to the backup directory.
#
# Layout under BACKUP_ROOT (default /var/backups/eduplatform):
#   db/eduplatform-<stamp>.dump   pg_dump custom format, one per run
#   uploads/<stamp>/              full snapshot of /opt/uploads; files unchanged since
#                                 the previous snapshot are hard links to it, so a
#                                 night costs only what was uploaded that day
#   uploads/latest                symlink to the newest complete snapshot
#   .last-success                 time of the last run that finished; --check reads it
#
# Everything is created root:<group of BACKUP_ROOT>, files 0640 and directories
# 2750, so an off-host puller in that group can read the backups and nothing else can.
#
# Settings, from the environment or /etc/default/eduplatform-backup:
#   DB_NAME          database to dump                          (eduplatform)
#   UPLOADS_DIR      media store                               (/opt/uploads)
#   BACKUP_ROOT      where backups go; must already exist      (/var/backups/eduplatform)
#   KEEP_DAYS        dumps and snapshots older than this go    (14)
#   MAX_AGE_HOURS    --check fails past this                   (26)
#   BACKUP_PING_URL  optional; fetched after every success, for a dead-man's-switch
#                    monitor that alerts when the ping stops arriving
set -euo pipefail
umask 027

if [ -r /etc/default/eduplatform-backup ]; then
    # shellcheck disable=SC1091
    . /etc/default/eduplatform-backup
fi

DB_NAME=${DB_NAME:-eduplatform}
UPLOADS_DIR=${UPLOADS_DIR:-/opt/uploads}
BACKUP_ROOT=${BACKUP_ROOT:-/var/backups/eduplatform}
KEEP_DAYS=${KEEP_DAYS:-14}
MAX_AGE_HOURS=${MAX_AGE_HOURS:-26}
BACKUP_PING_URL=${BACKUP_PING_URL:-}

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

check() {
    local marker="$BACKUP_ROOT/.last-success" age_h
    if [ ! -f "$marker" ]; then
        echo "STALE: no successful backup recorded ($marker does not exist)"
        exit 1
    fi
    age_h=$(( ($(date +%s) - $(stat -c %Y "$marker")) / 3600 ))
    if [ "$age_h" -ge "$MAX_AGE_HOURS" ]; then
        echo "STALE: last successful backup finished ${age_h}h ago ($(cat "$marker"))"
        exit 1
    fi
    echo "OK: last successful backup finished ${age_h}h ago ($(cat "$marker"))"
}

if [ "${1:-}" = "--check" ]; then
    check
    exit 0
fi
[ $# -eq 0 ] || die "usage: $0 [--check]"

[ "$(id -u)" -eq 0 ] || die "run as root"
command -v rsync >/dev/null || die "rsync is not installed (sudo apt install rsync)"
command -v pg_dump >/dev/null || die "pg_dump is not installed"
[ -d "$UPLOADS_DIR" ] || die "$UPLOADS_DIR does not exist"
# Not created here: a mistyped BACKUP_ROOT should fail, not quietly start a second
# backup tree that nothing copies off the host. DB_SETUP.md §9 creates it.
[ -d "$BACKUP_ROOT" ] || die "$BACKUP_ROOT does not exist; create it as in DB_SETUP.md §9"
if ! [[ "$KEEP_DAYS" =~ ^[1-9][0-9]*$ ]]; then
    die "KEEP_DAYS must be a positive integer"
fi

# Postgres cannot enter root's home, which is where cron starts; avoid the warning.
cd /

mkdir -p "$BACKUP_ROOT/db" "$BACKUP_ROOT/uploads"
chmod 2750 "$BACKUP_ROOT/db" "$BACKUP_ROOT/uploads"

# One run at a time. A second run, from cron and by hand at the same moment, would
# otherwise race on the `latest` link and on pruning.
exec 9>"$BACKUP_ROOT/.lock"
flock -n 9 || die "another backup is already running"

# Leftovers of a run that died half way. They were never renamed into place, so
# nothing refers to them.
find "$BACKUP_ROOT/db" -maxdepth 1 -name '.*.partial' -delete
find "$BACKUP_ROOT/uploads" -mindepth 1 -maxdepth 1 -name '.*.partial' -exec rm -rf -- {} +

# Seconds in the name, so two runs in the same minute cannot collide.
stamp=$(date +%F-%H%M%S)
db_tmp="$BACKUP_ROOT/db/.eduplatform-$stamp.dump.partial"
snap_tmp="$BACKUP_ROOT/uploads/.$stamp.partial"
trap 'rm -rf -- "$db_tmp" "$snap_tmp"' EXIT

# ── Database ─────────────────────────────────────────────────────────────────
log "dumping database $DB_NAME"
runuser -u postgres -- pg_dump --format=custom "$DB_NAME" >"$db_tmp"
# A dump pg_restore cannot read is not a backup. --list reads only the table of
# contents, so this costs a second, and it catches a truncated file now rather
# than on the day it is needed.
pg_restore --list "$db_tmp" >/dev/null || die "pg_restore cannot read the new dump"
db_file="$BACKUP_ROOT/db/eduplatform-$stamp.dump"
mv -T -- "$db_tmp" "$db_file"
log "database: $db_file ($(du -h "$db_file" | cut -f1))"

# ── Uploads ──────────────────────────────────────────────────────────────────
# A snapshot, not a tarball. The procedure this replaces wrote a full .tar.gz of
# /opt/uploads every night and kept 14 of them next to the database, so with
# lesson videos of up to 512 MB each the backups alone could fill the disk.
# --link-dest hard-links every file that is unchanged since the previous snapshot,
# so each snapshot is complete on its own but costs only the new files.
#
# No -o/-g: files are owned by root and take the directory's group, whatever uid
# the container wrote them as. --chmod makes the permissions the same every night,
# which --link-dest needs before it will reuse a file.
link_dest=()
if [ -d "$BACKUP_ROOT/uploads/latest/" ]; then
    link_dest=(--link-dest="$(readlink -f "$BACKUP_ROOT/uploads/latest")")
fi
log "copying $UPLOADS_DIR"
set +e
rsync -rlt --perms --chmod=D2750,F0640 "${link_dest[@]}" "$UPLOADS_DIR/" "$snap_tmp/"
rc=$?
set -e
case $rc in
    0) ;;
    # A file deleted between rsync listing it and copying it. The app deletes media
    # while running, so this is normal; the file is gone from the database too.
    24) log "note: some files vanished during the copy (deleted by the app meanwhile)" ;;
    *) die "rsync of $UPLOADS_DIR failed with exit code $rc" ;;
esac
mv -T -- "$snap_tmp" "$BACKUP_ROOT/uploads/$stamp"
ln -sfn -- "$stamp" "$BACKUP_ROOT/uploads/latest"
log "uploads: $BACKUP_ROOT/uploads/$stamp ($(find "$BACKUP_ROOT/uploads/$stamp" -type f | wc -l) files)"

# ── Retention ────────────────────────────────────────────────────────────────
# Only after both halves succeeded, so a run that keeps failing never eats the
# backups that still work. By the date in the name, not by mtime: rsync -t gives a
# snapshot directory the mtime of /opt/uploads itself, which can be months old.
# The snapshot `latest` points at is never pruned.
cutoff=$(date -d "-$KEEP_DAYS days" +%F)
current=$(readlink "$BACKUP_ROOT/uploads/latest")
for f in "$BACKUP_ROOT"/db/eduplatform-*.dump; do
    [ -e "$f" ] || continue
    day=$(basename "$f"); day=${day#eduplatform-}; day=${day:0:10}
    if [[ "$day" < "$cutoff" ]]; then
        log "pruning $f"
        rm -f -- "$f"
    fi
done
for s in "$BACKUP_ROOT"/uploads/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-*; do
    [ -d "$s" ] || continue
    name=$(basename "$s")
    [ "$name" = "$current" ] && continue
    if [[ "${name:0:10}" < "$cutoff" ]]; then
        log "pruning $s"
        rm -rf -- "$s"
    fi
done

# ── Success ──────────────────────────────────────────────────────────────────
# The one signal that the whole run worked. Cron discards output on a host without
# mail, so a failing backup is otherwise silent; `--check` and BACKUP_PING_URL are
# how anyone finds out.
printf '%s db=%s uploads=%s\n' "$(date -u +%FT%TZ)" "$(basename "$db_file")" "$stamp" \
    >"$BACKUP_ROOT/.last-success.tmp"
mv -T -- "$BACKUP_ROOT/.last-success.tmp" "$BACKUP_ROOT/.last-success"
log "backup complete"

if [ -n "$BACKUP_PING_URL" ]; then
    curl -fsS --max-time 10 --retry 3 -o /dev/null "$BACKUP_PING_URL" \
        || log "warning: the backup succeeded but BACKUP_PING_URL could not be reached"
fi
