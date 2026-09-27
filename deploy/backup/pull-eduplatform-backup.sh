#!/usr/bin/env bash
# Runs on the BACKUP host, not on the trainings server. Copies the server's newest
# complete backup (made by backup-eduplatform.sh) to this machine. DB_SETUP.md §9
# has the setup on both sides.
#
# Pull, not push. A server that can push to the backup host can also overwrite or
# delete what is there, so an intruder on the server, or a mistaken `rm -rf` there,
# would reach the off-host copies too. Here the server only ever answers reads: the
# login this script uses is forced to `rrsync -ro /var/backups/eduplatform`.
#
# Never --delete. The server prunes its copies after 14 days; this side keeps its
# own, for KEEP_DAYS, so a pruning or wiping on the server never propagates.
#
#   SOURCE=backup-pull@trainings.aztu.edu.az pull-eduplatform-backup
#   pull-eduplatform-backup --check     exit 1 if the last pull is older than MAX_AGE_HOURS
#
# Settings (environment, or /etc/default/pull-eduplatform-backup):
#   SOURCE         user@host of the server's backup-pull login         (required)
#   DEST           where copies go on this machine                     (/srv/backups/trainings)
#   KEEP_DAYS      how long this side keeps dumps and snapshots        (90)
#   MAX_AGE_HOURS  --check fails past this                             (30)
#   RSYNC_RSH      e.g. "ssh -i /root/.ssh/trainings_backup" for a dedicated key
set -euo pipefail
umask 077

if [ -r /etc/default/pull-eduplatform-backup ]; then
    # shellcheck disable=SC1091
    . /etc/default/pull-eduplatform-backup
fi

DEST=${DEST:-/srv/backups/trainings}
KEEP_DAYS=${KEEP_DAYS:-90}
MAX_AGE_HOURS=${MAX_AGE_HOURS:-30}
export RSYNC_RSH=${RSYNC_RSH:-ssh}

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

if [ "${1:-}" = "--check" ]; then
    marker="$DEST/.last-pull"
    [ -f "$marker" ] || { echo "STALE: nothing pulled yet ($marker does not exist)"; exit 1; }
    age_h=$(( ($(date +%s) - $(stat -c %Y "$marker")) / 3600 ))
    if [ "$age_h" -ge "$MAX_AGE_HOURS" ]; then
        echo "STALE: last pull finished ${age_h}h ago ($(cat "$marker"))"
        exit 1
    fi
    echo "OK: last pull finished ${age_h}h ago ($(cat "$marker"))"
    exit 0
fi
[ $# -eq 0 ] || die "usage: $0 [--check]"
[ -n "${SOURCE:-}" ] || die "set SOURCE, e.g. SOURCE=backup-pull@trainings.aztu.edu.az"
if ! [[ "$KEEP_DAYS" =~ ^[1-9][0-9]*$ ]]; then
    die "KEEP_DAYS must be a positive integer"
fi

mkdir -p "$DEST/db" "$DEST/uploads"
exec 9>"$DEST/.lock"
flock -n 9 || die "another pull is already running"
find "$DEST/uploads" -mindepth 1 -maxdepth 1 -name '.*.partial' -exec rm -rf -- {} +

# Which run is the newest complete one. The server writes .last-success last, so
# the dump and snapshot it names are finished; anything newer may still be in
# progress and is ignored.
rsync "$SOURCE:.last-success" "$DEST/.remote-last-success"
db_file=$(sed -n 's/.* db=\([^ ]*\).*/\1/p' "$DEST/.remote-last-success")
stamp=$(sed -n 's/.* uploads=\([^ ]*\).*/\1/p' "$DEST/.remote-last-success")
[[ "$db_file" =~ ^eduplatform-[0-9-]+\.dump$ ]] || die "unexpected .last-success on the server: $(cat "$DEST/.remote-last-success")"
[[ "$stamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]+$ ]] || die "unexpected .last-success on the server: $(cat "$DEST/.remote-last-success")"

# Database dump. --ignore-existing: a dump already here is never overwritten, even
# if the file of that name on the server has changed since.
log "pulling db/$db_file"
rsync -t --ignore-existing "$SOURCE:db/$db_file" "$DEST/db/"
pg_restore --list "$DEST/db/$db_file" >/dev/null 2>&1 \
    || log "warning: could not verify $db_file with pg_restore (not installed here, or the file is damaged)"

# Uploads snapshot, hard-linked to the previous one on THIS side, so each night
# costs only the new files here as well.
if [ -d "$DEST/uploads/$stamp" ]; then
    log "uploads/$stamp is already here"
else
    link_dest=()
    if [ -d "$DEST/uploads/latest/" ]; then
        link_dest=(--link-dest="$(readlink -f "$DEST/uploads/latest")")
    fi
    log "pulling uploads/$stamp"
    rsync -rt "${link_dest[@]}" "$SOURCE:uploads/$stamp/" "$DEST/uploads/.$stamp.partial/"
    mv -T -- "$DEST/uploads/.$stamp.partial" "$DEST/uploads/$stamp"
    ln -sfn -- "$stamp" "$DEST/uploads/latest"
fi

# Retention on this side, by the date in the name. Never the newest snapshot.
cutoff=$(date -d "-$KEEP_DAYS days" +%F)
current=$(readlink "$DEST/uploads/latest")
for f in "$DEST"/db/eduplatform-*.dump; do
    [ -e "$f" ] || continue
    day=$(basename "$f"); day=${day#eduplatform-}; day=${day:0:10}
    if [[ "$day" < "$cutoff" ]]; then log "pruning $f"; rm -f -- "$f"; fi
done
for s in "$DEST"/uploads/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-*; do
    [ -d "$s" ] || continue
    name=$(basename "$s")
    [ "$name" = "$current" ] && continue
    if [[ "${name:0:10}" < "$cutoff" ]]; then log "pruning $s"; rm -rf -- "$s"; fi
done

printf '%s db=%s uploads=%s\n' "$(date -u +%FT%TZ)" "$db_file" "$stamp" >"$DEST/.last-pull.tmp"
mv -T -- "$DEST/.last-pull.tmp" "$DEST/.last-pull"
log "pull complete: db/$db_file, uploads/$stamp"
