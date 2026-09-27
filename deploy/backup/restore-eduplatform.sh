#!/usr/bin/env bash
# Restores the trainings platform's production database, and optionally its
# uploads, from backups made by backup-eduplatform.sh. Run as root on the host.
#
#   restore-eduplatform --db /var/backups/eduplatform/db/eduplatform-<stamp>.dump \
#                       [--uploads /var/backups/eduplatform/uploads/<stamp>] [--yes]
#
# Take the uploads snapshot with the same <stamp> as the dump: they were made
# minutes apart by the same run.
#
# Nothing is dropped. The live database is renamed to <db>_pre_restore_<stamp> and
# the live uploads directory to <dir>.pre-restore-<stamp>, so restoring the wrong
# backup can be undone with two renames. The script prints the commands to delete
# them once the restore has been checked.
#
# Order, and why:
#   1. Check the dump is a readable archive and the snapshot exists, before
#      anything is touched.
#   2. Copy the snapshot to a staging directory while the site is still up; this
#      is the slow part.
#   3. Stop the backend, so nothing writes while the database is swapped.
#   4. Rename the live database away, create an empty one owned by the app role,
#      and restore into it in one transaction with --exit-on-error. If that fails,
#      the empty database is dropped, the old one renamed back and the backend
#      started again, so a failed restore changes nothing.
#   5. Swap the staged uploads in and give them to the container's user (1001).
#   6. Start the backend and wait for readiness. Flyway applies any migration
#      newer than the dump at this point. Exit non-zero if it is not ready in time.
#
# Settings (environment):
#   DB_NAME        (eduplatform)      DB_OWNER       (eduplatform)
#   UPLOADS_DIR    (/opt/uploads)     APP_UID/GID    (1001/1001)
#   API_DIR        (/opt/trainings/api-trainings.aztu.edu.az)
#   READINESS_URL  (http://127.0.0.1:8080/actuator/health/readiness)
#   READY_TIMEOUT  seconds            (300)
set -euo pipefail
umask 022

DB_NAME=${DB_NAME:-eduplatform}
DB_OWNER=${DB_OWNER:-eduplatform}
UPLOADS_DIR=${UPLOADS_DIR:-/opt/uploads}
APP_UID=${APP_UID:-1001}
APP_GID=${APP_GID:-1001}
API_DIR=${API_DIR:-/opt/trainings/api-trainings.aztu.edu.az}
READINESS_URL=${READINESS_URL:-http://127.0.0.1:8080/actuator/health/readiness}
READY_TIMEOUT=${READY_TIMEOUT:-300}

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
usage() { die "usage: $0 --db <dump> [--uploads <snapshot dir>] [--yes]"; }

dump='' snap='' assume_yes=false
while [ $# -gt 0 ]; do
    case $1 in
        --db) [ $# -ge 2 ] || usage; dump=$2; shift 2 ;;
        --uploads) [ $# -ge 2 ] || usage; snap=${2%/}; shift 2 ;;
        --yes) assume_yes=true; shift ;;
        *) usage ;;
    esac
done
[ -n "$dump" ] || usage

[ "$(id -u)" -eq 0 ] || die "run as root"
# The names go into SQL as identifiers; keep them to what Postgres needs no quoting for.
for n in "$DB_NAME" "$DB_OWNER"; do
    [[ "$n" =~ ^[a-z_][a-z0-9_]*$ ]] || die "unexpected database or role name: $n"
done
compose=(docker compose -f "$API_DIR/docker-compose.prod.yml")
[ -f "$API_DIR/docker-compose.prod.yml" ] || die "$API_DIR/docker-compose.prod.yml not found (set API_DIR)"

cd /
stamp=$(date +%Y%m%d%H%M%S)
old_db="${DB_NAME}_pre_restore_${stamp}"

# ── 1. Preflight ─────────────────────────────────────────────────────────────
[ -f "$dump" ] || die "$dump does not exist"
pg_restore --list "$dump" >/dev/null || die "$dump is not a readable pg_dump archive; nothing was changed"
if [ -n "$snap" ]; then
    [ -d "$snap" ] || die "$snap is not a directory; nothing was changed"
    # The swap below is two renames, and a mount point cannot be renamed.
    if mountpoint -q "$UPLOADS_DIR"; then
        die "$UPLOADS_DIR is a mount point, so it cannot be swapped. Restore the database without --uploads, then copy the files in by hand with the backend stopped: rsync -a --delete $snap/ $UPLOADS_DIR/ && chown -R $APP_UID:$APP_GID $UPLOADS_DIR && chmod -R u=rwX,go=rX $UPLOADS_DIR"
    fi
    need=$(du -sb "$snap" | cut -f1)
    free=$(df --output=avail -B1 "$(dirname "$UPLOADS_DIR")" | tail -1)
    [ "$free" -gt "$need" ] || die "not enough free space next to $UPLOADS_DIR to stage $snap ($need bytes needed, $free free)"
fi
runuser -u postgres -- psql -XtAc "select 1 from pg_roles where rolname = '$DB_OWNER'" | grep -q 1 \
    || die "role $DB_OWNER does not exist"

log "about to restore:"
log "  database $DB_NAME from $dump (the current one is kept as $old_db)"
[ -z "$snap" ] || log "  $UPLOADS_DIR from $snap (the current one is kept as $UPLOADS_DIR.pre-restore-$stamp)"
log "  the backend is stopped for the duration"
if ! $assume_yes; then
    read -r -p "Type RESTORE to continue: " answer </dev/tty
    [ "$answer" = "RESTORE" ] || die "aborted; nothing was changed"
fi

# ── 2. Stage the uploads while the site is still up ──────────────────────────
staging=''
if [ -n "$snap" ]; then
    staging="$UPLOADS_DIR.restoring-$stamp"
    log "staging $snap at $staging"
    rsync -a "$snap/" "$staging/"
    # The snapshot is root:<backup group> 0640/2750; the container writes as 1001.
    chown -R "$APP_UID:$APP_GID" "$staging"
    chmod -R u=rwX,go=rX "$staging"
fi

# ── 3. Stop the backend ──────────────────────────────────────────────────────
log "stopping the backend"
"${compose[@]}" stop backend

start_backend() {
    log "starting the backend"
    "${compose[@]}" up -d --no-build backend
}

# ── 4. Swap the database ─────────────────────────────────────────────────────
# Anything still connected (a psql session, a monitoring query) would block the
# rename; the backend is already stopped, so nothing reconnects.
log "renaming $DB_NAME to $old_db"
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -v db="$DB_NAME" -v old="$old_db" -d postgres <<'SQL' >/dev/null
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'db' AND pid <> pg_backend_pid();
ALTER DATABASE :"db" RENAME TO :"old";
SQL

rollback_db() {
    log "restore failed; putting the previous database back"
    runuser -u postgres -- dropdb --if-exists "$DB_NAME" || true
    runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -v db="$DB_NAME" -v old="$old_db" -d postgres <<'SQL' >/dev/null
ALTER DATABASE :"old" RENAME TO :"db";
SQL
    [ -z "$staging" ] || rm -rf -- "$staging"
    start_backend
    die "the restore did not complete; $DB_NAME is back as it was and the backend has been started again"
}

log "creating an empty $DB_NAME owned by $DB_OWNER"
runuser -u postgres -- createdb --owner="$DB_OWNER" --encoding=UTF8 --template=template0 "$DB_NAME" || rollback_db
log "restoring $dump"
# The dump is opened here, by root, and handed over as stdin: postgres has no
# access to the backup directory, and a redirected regular file is seekable, which
# a custom-format restore needs. --single-transaction with --exit-on-error means
# the first error undoes everything instead of leaving half a database.
if ! runuser -u postgres -- pg_restore --exit-on-error --single-transaction --dbname="$DB_NAME" <"$dump"; then
    rollback_db
fi
# pg_restore does not collect planner statistics; without them the first queries
# after a restore can pick bad plans.
runuser -u postgres -- vacuumdb --analyze-only --quiet "$DB_NAME"

# ── 5. Swap the uploads ──────────────────────────────────────────────────────
if [ -n "$staging" ]; then
    log "swapping in the restored uploads"
    if [ -e "$UPLOADS_DIR" ]; then
        mv -T -- "$UPLOADS_DIR" "$UPLOADS_DIR.pre-restore-$stamp"
    fi
    mv -T -- "$staging" "$UPLOADS_DIR"
fi

# ── 6. Start and wait for readiness ──────────────────────────────────────────
start_backend
log "waiting up to ${READY_TIMEOUT}s for $READINESS_URL"
deadline=$((SECONDS + READY_TIMEOUT))
until curl -fsS --max-time 5 -o /dev/null "$READINESS_URL" 2>/dev/null; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        die "the backend is not ready after ${READY_TIMEOUT}s. The data is restored; check \`${compose[*]} logs --tail=200 backend\`. To go back: stop the backend, drop $DB_NAME, rename $old_db to $DB_NAME$([ -z "$snap" ] || echo ", and move $UPLOADS_DIR.pre-restore-$stamp back to $UPLOADS_DIR")."
    fi
    sleep 5
done

log "restore complete and the backend is ready."
log "once you have checked the site, delete what was kept:"
log "  sudo -u postgres dropdb $old_db"
[ -z "$snap" ] || log "  sudo rm -rf $UPLOADS_DIR.pre-restore-$stamp"
