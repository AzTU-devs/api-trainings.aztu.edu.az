#!/usr/bin/env bash
# Deploys the AzTU trainings platform (API, dashboard, public site) to pinned commits.
#
# Run as root on the server, under systemd-run so an SSH drop cannot kill it half way,
# with the three commits to deploy (the script is taken from the API commit itself):
#
#   curl -fsSL -o /root/deploy-trainings.sh \
#     https://raw.githubusercontent.com/AzTU-devs/api-trainings.aztu.edu.az/<api sha>/deploy/deploy-trainings.sh
#   chmod 700 /root/deploy-trainings.sh
#   systemd-run --unit=deploy-trainings --collect --setenv=HOME=/root --setenv=PATH="$PATH" \
#     --setenv=WANT_API=<api sha> --setenv=WANT_ADMIN=<dashboard sha> --setenv=WANT_WEB=<site sha> \
#     /root/deploy-trainings.sh
#   journalctl -fu deploy-trainings -o cat
#
# Stages:
#   preflight  read-only checks; nothing changed yet
#   changed    repos fast-forwarded, images built, backup taken; old containers still serve
#   swapped    at least one container replaced
#   done       finished
# On failure before the swap everything is put back automatically. After the swap the
# script prints the exact rollback commands for whatever it replaced.
set -Eeuo pipefail
trap '' HUP

declare -A WANT=([api]=${WANT_API:?set WANT_API to the API commit} [admin]=${WANT_ADMIN:?set WANT_ADMIN to the dashboard commit} [web]=${WANT_WEB:?set WANT_WEB to the public site commit})
declare -A CT=([api]=eduplatform-backend [admin]=eduplatform-admin [web]=eduplatform-frontend)
TARGET_DB_VERSION=  # the highest Flyway version at WANT[api]; read once the repos are fetched
REQUIRED_ORIGINS=(https://trainings.aztu.edu.az https://dashboard-trainings.aztu.edu.az)
HOSTS=(trainings.aztu.edu.az dashboard-trainings.aztu.edu.az api-trainings.aztu.edu.az)
STATE=/root/deploy-trainings.state
LIVE=/etc/nginx/sites-available/trainings
SCRATCH_DB=eduplatform_predeploy
SCRATCH_CT=eduplatform-predeploy
SCRATCH_UPLOADS=/root/deploy-trainings-predeploy-uploads
TS=$(date +%Y%m%d-%H%M%S)
PRE_TS=$TS
LOG=/root/deploy-trainings-$TS.log
install -m 600 /dev/null "$LOG"
exec > >(tee -a "$LOG") 2>&1

STAGE=preflight
FAILS=0
declare -A DIR PROJ FILE OLDREV OWNER IMG
SWAPPED=()
BACKUP=""
NGINX_BAK=""

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32mok\033[0m  %s\n' "$*"; }
note() { printf '   \033[33mnote\033[0m %s\n' "$*"; }
bad()  { printf '   \033[31mFAIL\033[0m %s\n' "$*"; FAILS=$((FAILS + 1)); }
die()  { printf '\n\033[1;31mSTOP: %s\033[0m\n' "$*"; exit 1; }

# Reads KEY from a compose-style .env: optional `export`, quotes, trailing comments.
env_get() {
    local file=$1 key=$2
    [ -f "$file" ] || return 0
    awk -v k="$key" '
        { sub(/^[ \t]*export[ \t]+/, "") }
        $0 ~ "^[ \t]*" k "[ \t]*=" {
            v = $0; sub(/^[^=]*=[ \t]*/, "", v)
            if (v ~ /^"/) { sub(/^"/, "", v); sub(/".*$/, "", v) }
            else if (v ~ /^'\''/) { sub(/^'\''/, "", v); sub(/'\''.*$/, "", v) }
            else { sub(/[ \t]+#.*$/, "", v); sub(/[ \t]+$/, "", v) }
            val = v
        }
        END { if (val != "") print val }' "$file"
}

label() { docker inspect -f "{{ index .Config.Labels \"com.docker.compose.$2\" }}" "$1" 2>/dev/null || true; }

# git as the repo's owner, so files keep their owner and mode.
g() {
    local repo=$1; shift
    runuser -u "${OWNER[$repo]}" -- sh -c 'umask 022; cd "$0" && exec git "$@"' "${DIR[$repo]}" "$@"
}
psqlq() { runuser -u postgres -- psql -X -d "$1" -Atc "$2"; }

wait_ready() { # name url seconds [container]
    local name=$1 url=$2 secs=$3 ct=${4:-} i state
    for ((i = 0; i < secs; i += 3)); do
        if curl -fsS -o /dev/null --max-time 3 "$url"; then ok "$name ready"; return 0; fi
        if [ -n "$ct" ]; then
            state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$ct" 2>/dev/null || echo "missing")
            case $state in "running 0") ;; *) bad "$name container is $state"; docker logs --tail 80 "$ct" 2>&1 | sed 's/^/      /'; return 1 ;; esac
        fi
        sleep 3
    done
    bad "$name not ready after ${secs}s ($url)"
    [ -z "$ct" ] || docker logs --tail 80 "$ct" 2>&1 | sed 's/^/      /'
    return 1
}

save_state() {
    {
        echo "TS=$TS"; echo "STAGE=$STAGE"; echo "BACKUP=$BACKUP"; echo "NGINX_BAK=$NGINX_BAK"
        for r in api admin web; do echo "OLDREV_$r=${OLDREV[$r]:-}"; echo "DIR_$r=${DIR[$r]:-}"; done
        echo "SWAPPED=${SWAPPED[*]:-}"
    } > "$STATE"
    chmod 600 "$STATE"
}

cleanup_scratch() {
    docker rm -f "$SCRATCH_CT" >/dev/null 2>&1 || true
    runuser -u postgres -- dropdb --if-exists "$SCRATCH_DB" >/dev/null 2>&1 || true
    rm -rf "$SCRATCH_UPLOADS" /root/deploy-trainings-predeploy.env
}

undo_before_swap() {
    note "putting everything back (nothing was swapped)"
    for r in api admin web; do
        [ -n "${OLDREV[$r]:-}" ] || continue
        g "$r" reset -q --keep "${OLDREV[$r]}" && ok "$r repo back at ${OLDREV[$r]:0:7}" || note "$r: reset --keep refused; check ${DIR[$r]} by hand"
        img=${IMG[$r]:-}
        if [ -n "$img" ] && docker image inspect "${img%%:*}:pre-$PRE_TS" >/dev/null 2>&1; then
            docker tag "${img%%:*}:pre-$PRE_TS" "$img" && ok "$r image tag restored"
        fi
        [ -f "${DIR[$r]}/.env.bak.$TS" ] && cp -p "${DIR[$r]}/.env.bak.$TS" "${DIR[$r]}/.env"
    done
    rm -f "$STATE"
}

rollback_help() {
    printf '\n\033[1;33mRollback commands for what was replaced:\033[0m\n'
    for r in "${SWAPPED[@]}"; do
        local img=${IMG[$r]}
        if [ "$r" = api ]; then
            cat <<EOF
  # API (code, image and database):
  runuser -u ${OWNER[api]} -- git -C ${DIR[api]} reset --keep ${OLDREV[api]}
  docker tag ${img%%:*}:pre-$PRE_TS ${img}
  COMPOSE_PROJECT_NAME=${PROJ[api]} API_DIR=${DIR[api]} DB_NAME=$DB_NAME DB_OWNER=$DB_USER \\
      restore-eduplatform --db $BACKUP
EOF
        else
            cat <<EOF
  # $r:
  runuser -u ${OWNER[$r]} -- git -C ${DIR[$r]} reset --keep ${OLDREV[$r]}
  docker tag ${img%%:*}:pre-$PRE_TS ${img}
  docker compose -p ${PROJ[$r]} -f ${FILE[$r]} up -d --no-build
EOF
        fi
    done
    [ -z "$NGINX_BAK" ] || printf '  # nginx:\n  cp -p %s %s && nginx -t && systemctl reload nginx\n' "$NGINX_BAK" "$LIVE"
    printf '  Full log: %s\n' "$LOG"
}

on_exit() {
    local rc=$?
    [ "$rc" -eq 0 ] && [ "$STAGE" = done ] && return
    cleanup_scratch
    case $STAGE in
        preflight) printf '\nStopped during checks. Nothing was changed. Log: %s\n' "$LOG" ;;
        changed)   undo_before_swap; printf '\nStopped before any container was replaced; repos and images are back as they were. Log: %s\n' "$LOG" ;;
        swapped)   save_state; rollback_help ;;
    esac
}
trap on_exit EXIT

[ -e "$STATE" ] && die "$STATE exists: an earlier run did not finish. Read it and its log, sort that out, then delete the file."

# ───────────────────────────── preflight ─────────────────────────────
step "Tools"
for t in docker git curl runuser psql pg_dump pg_restore openssl nginx systemctl awk ss; do
    command -v "$t" >/dev/null || die "$t is not installed"
done
docker compose version >/dev/null 2>&1 || die "docker compose plugin missing"
ok "all present"

step "Running services"
for r in api admin web; do
    docker inspect "${CT[$r]}" >/dev/null 2>&1 || die "container ${CT[$r]} not found"
    DIR[$r]=$(label "${CT[$r]}" project.working_dir)
    PROJ[$r]=$(label "${CT[$r]}" project)
    FILE[$r]=$(label "${CT[$r]}" project.config_files)
    [ -n "${DIR[$r]}" ] && [ -d "${DIR[$r]}/.git" ] || die "${CT[$r]}: compose working dir '${DIR[$r]}' is not a git checkout"
    case ${FILE[$r]} in *,*) die "${CT[$r]} was started with several compose files (${FILE[$r]}); deploy it by hand" ;; esac
    [ "$(basename "${FILE[$r]}")" = docker-compose.prod.yml ] || die "${CT[$r]} runs from ${FILE[$r]}, expected docker-compose.prod.yml"
    OWNER[$r]=$(stat -c %U "${DIR[$r]}/.git")
    IMG[$r]=$(docker inspect -f '{{.Config.Image}}' "${CT[$r]}")
    case ${IMG[$r]} in *:*) ;; *) IMG[$r]=${IMG[$r]}:latest ;; esac
    ok "$r: ${DIR[$r]} (project ${PROJ[$r]}, owner ${OWNER[$r]})"
done

step "Repositories"
for r in api admin web; do
    br=$(g "$r" rev-parse --abbrev-ref HEAD)
    [ "$br" = main ] || die "$r is on branch $br, not main"
    [ -z "$(g "$r" status --porcelain --untracked-files=no)" ] || { g "$r" status --short; die "$r has local changes to tracked files"; }
    g "$r" fetch -q origin main
    g "$r" cat-file -e "${WANT[$r]}^{commit}" 2>/dev/null || die "$r: commit ${WANT[$r]} not found after fetch"
    OLDREV[$r]=$(g "$r" rev-parse HEAD)
    if [ "${OLDREV[$r]}" = "${WANT[$r]}" ]; then ok "$r already at ${WANT[$r]:0:7}"; continue; fi
    g "$r" merge-base --is-ancestor "${OLDREV[$r]}" "${WANT[$r]}" || die "$r: ${OLDREV[$r]:0:7} is not an ancestor of ${WANT[$r]:0:7} (cannot fast-forward)"
    ok "$r ${OLDREV[$r]:0:7} → ${WANT[$r]:0:7}:"
    g "$r" log --oneline --no-decorate "${OLDREV[$r]}..${WANT[$r]}" | sed 's/^/      /'
done

TARGET_DB_VERSION=$(g api ls-tree --name-only "${WANT[api]}" src/main/resources/db/migration/ \
    | sed -n 's#.*/V\([0-9][0-9]*\)__.*#\1#p' | sort -n | tail -1)
[ -n "$TARGET_DB_VERSION" ] || die "no migrations found at ${WANT[api]:0:7}"

step "API settings (${DIR[api]}/.env)"
AENV=${DIR[api]}/.env
[ -f "$AENV" ] || die "$AENV missing"
DB_URL=$(env_get "$AENV" DATABASE_URL); DB_USER=$(env_get "$AENV" DATABASE_USERNAME)
[ -n "$DB_URL" ] && [ -n "$DB_USER" ] || die "DATABASE_URL / DATABASE_USERNAME not set"
DB_NAME=${DB_URL##*/}; DB_NAME=${DB_NAME%%\?*}
case $DB_URL in jdbc:postgresql://127.0.0.1:*|jdbc:postgresql://localhost:*) ok "database $DB_NAME on this host" ;;
    *) die "DATABASE_URL does not point at this host's Postgres; this script backs up and dry-runs locally only" ;; esac
JWT=$(env_get "$AENV" JWT_ACCESS_SECRET)
[ "${#JWT}" -ge 32 ] || die "JWT_ACCESS_SECRET is shorter than 32 characters"
case ${JWT,,} in *change-me*|*dev-only*|*insecure*) die "JWT_ACCESS_SECRET is a placeholder" ;; esac
ok "JWT secret set (${#JWT} chars)"
CORS=$(env_get "$AENV" CORS_ALLOWED_ORIGINS)
WANT_CORS=$(IFS=,; echo "${REQUIRED_ORIGINS[*]}")
if [ "${CORS// /}" = "$WANT_CORS" ]; then ok "CORS: $WANT_CORS"
else note "CORS_ALLOWED_ORIGINS is '${CORS:-<unset>}'; it will be set to exactly $WANT_CORS"; fi
prof=$(env_get "$AENV" SPRING_PROFILES_ACTIVE)
case $prof in ''|prod) ;; *) die "SPRING_PROFILES_ACTIVE=$prof in .env; remove it (compose pins prod)" ;; esac
grep -Eq '^[[:space:]]*(export[[:space:]]+)?SPRING_FLYWAY_LOCATIONS' "$AENV" && die "SPRING_FLYWAY_LOCATIONS is set in .env; remove it"
grep -Eq '^[[:space:]]*(export[[:space:]]+)?SPRING_SERVLET_MULTIPART_' "$AENV" && die "SPRING_SERVLET_MULTIPART_* in .env overrides the upload limits; remove those lines"
port=$(env_get "$AENV" SERVER_PORT); [ -z "$port" ] || [ "$port" = 8080 ] || die "SERVER_PORT=$port; nginx expects 8080"
sw=$(env_get "$AENV" SWAGGER_ENABLED); [ "${sw,,}" != true ] || die "SWAGGER_ENABLED=true in production; set it to false"
ok "profile, migrations, multipart, port, swagger"
rl=$(env_get "$AENV" RATE_LIMIT_ENABLED); [ "${rl,,}" != false ] || note "RATE_LIMIT_ENABLED=false: sign-in, OTP and password-reset are not throttled. Remove the line unless that is deliberate."
cs=$(env_get "$AENV" COOKIE_SECURE); [ "${cs,,}" != false ] || note "COOKIE_SECURE=false: the refresh cookie can travel over plain http. Remove the line."
asr=$(env_get "$AENV" ADMIN_SELF_REGISTER); [ "${asr,,}" != true ] || note "ADMIN_SELF_REGISTER=true: admin sign-up is open while no admin exists. Set it to false once the first admin is in."

step "Public site settings (${DIR[web]}/.env)"
WENV=${DIR[web]}/.env
[ -f "$WENV" ] || die "$WENV missing"
p=$(env_get "$WENV" PORT); [ -z "$p" ] || [ "$p" = 3000 ] || die "PORT=$p; nginx expects 3000"
api_int=$(env_get "$WENV" INTERNAL_API_URL)
case $api_int in
    http://127.0.0.1:8080*) ok "INTERNAL_API_URL=$api_int" ;;
    http://localhost:8080*) note "INTERNAL_API_URL=$api_int will become http://127.0.0.1:8080… (the API now listens on IPv4 loopback only, and localhost can resolve to ::1)" ;;
    '') note "INTERNAL_API_URL not set; server-side calls use NEXT_PUBLIC_API_URL through nginx" ;;
    *) note "INTERNAL_API_URL=$api_int (not loopback; check it still reaches the API after the swap)" ;; esac
for k in SHOWCASE_DATA SHOWCASE_EXPERTS; do note "$k=$(env_get "$WENV" $k || true) (empty = default: catalogue sample on, experts real)"; done

step "Database"
psqlq postgres "select 1" >/dev/null || die "cannot reach Postgres as postgres over the local socket"
psqlq postgres "select 1 from pg_database where datname='$DB_NAME'" | grep -q 1 || die "database $DB_NAME not found"
if [ -f /etc/default/eduplatform-backup ]; then
    bdb=$(env_get /etc/default/eduplatform-backup DB_NAME)
    [ -z "$bdb" ] || [ "$bdb" = "$DB_NAME" ] || die "/etc/default/eduplatform-backup has DB_NAME=$bdb, the API uses $DB_NAME"
fi
psqlq "$DB_NAME" "select 1 from flyway_schema_history where version in ('6','7') and script like '%dev%'" | grep -q 1 \
    && die "dev seed migrations are recorded in $DB_NAME; stop and investigate"
[ "$(psqlq "$DB_NAME" "select count(*) from flyway_schema_history where not success")" = 0 ] || die "failed Flyway rows in $DB_NAME"
DBVER=$(psqlq "$DB_NAME" "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+$'")
DBSIZE=$(psqlq "$DB_NAME" "select pg_database_size('$DB_NAME')")
ok "$DB_NAME at V$DBVER, $((DBSIZE / 1024 / 1024)) MB; this release brings it to V$TARGET_DB_VERSION"

step "Disk space"
BROOT=$(env_get /etc/default/eduplatform-backup BACKUP_ROOT 2>/dev/null || true); BROOT=${BROOT:-/var/backups/eduplatform}
PGDATA=$(psqlq postgres "show data_directory")
mkdir -p "$BROOT"
need_docker=$((12 * 1024 * 1024 * 1024)); need_pg=$((2 * DBSIZE + 512 * 1024 * 1024))
uploads_size=$(du -sb /opt/uploads 2>/dev/null | cut -f1 || echo 0)
[ -e "$BROOT/uploads/latest" ] && need_backup=$((DBSIZE + 256 * 1024 * 1024)) || need_backup=$((DBSIZE + uploads_size + 256 * 1024 * 1024))
declare -A NEED
add_need() { local fs; fs=$(df --output=target "$1" | tail -1); NEED[$fs]=$(( ${NEED[$fs]:-0} + $2 )); }
add_need "$(docker info -f '{{.DockerRootDir}}')" $need_docker
add_need "$PGDATA" $need_pg
add_need "$BROOT" $need_backup
for fs in "${!NEED[@]}"; do
    free=$(df --output=avail -B1 "$fs" | tail -1)
    [ "$free" -gt "${NEED[$fs]}" ] || die "$fs has $((free / 1024 / 1024)) MB free, needs $((NEED[$fs] / 1024 / 1024)) MB"
    ok "$fs: $((free / 1024 / 1024)) MB free (need $((NEED[$fs] / 1024 / 1024)) MB)"
done

step "nginx"
[ -L /etc/nginx/sites-enabled/trainings ] || [ -f /etc/nginx/sites-enabled/trainings ] || die "/etc/nginx/sites-enabled/trainings is not enabled"
nginx -t >/dev/null 2>&1 || die "nginx -t fails before any change; fix nginx first"
ok "enabled and valid"

# The new file, from the commit being deployed, with each server block given the certificate
# the live file uses for that name. Prepared and checked here, before anything changes;
# installed after the containers are swapped.
NEWCONF=/root/deploy-trainings-nginx-$TS.conf
g api show "${WANT[api]}:deploy/nginx/trainings.conf" > "$NEWCONF" || die "deploy/nginx/trainings.conf missing at ${WANT[api]:0:7}"
# Prints "name cert key" for every server block that has a certificate (comments ignored).
certmap() {
    awk '
        { s = $0; sub(/#.*/, "", s) }
        depth == 0 && s ~ /^[ \t]*server[ \t]*\{/ { inb = 1; names = ""; cert = ""; key = "" }
        inb && s ~ /^[ \t]*server_name[ \t]/ { t = s; sub(/^[ \t]*server_name[ \t]+/, "", t); sub(/;.*/, "", t); names = names " " t }
        inb && s ~ /^[ \t]*ssl_certificate[ \t]/ { t = s; sub(/^[ \t]*ssl_certificate[ \t]+/, "", t); sub(/;.*/, "", t); cert = t }
        inb && s ~ /^[ \t]*ssl_certificate_key[ \t]/ { t = s; sub(/^[ \t]*ssl_certificate_key[ \t]+/, "", t); sub(/;.*/, "", t); key = t }
        {
            o = gsub(/\{/, "{", s); c = gsub(/\}/, "}", s); depth += o - c
            if (inb && depth == 0 && (o || c)) {
                n = split(names, a, " ")
                for (i = 1; i <= n; i++) if (cert != "") print a[i], cert, key
                inb = 0
            }
        }' "$1"
}
declare -A CERT KEY
while read -r n c k; do CERT[$n]=$c; KEY[$n]=$k; done < <(certmap "$NEWCONF")
while read -r n c k; do CERT[$n]=$c; KEY[$n]=$k; done < <(certmap "$LIVE")
for h in "${HOSTS[@]}"; do
    [ -n "${CERT[$h]:-}" ] && [ -f "${CERT[$h]}" ] && [ -f "${KEY[$h]}" ] || die "no certificate found for $h (looked in $LIVE and the repo file)"
    openssl x509 -in "${CERT[$h]}" -noout -checkhost "$h" | grep -q 'does match' || die "${CERT[$h]} does not cover $h"
    openssl x509 -in "${CERT[$h]}" -noout -checkend 86400 >/dev/null || die "${CERT[$h]} expires within a day"
    ok "$h: ${CERT[$h]}"
done
# The catch-all block (server_name _) presents the public site's certificate.
CERT[_]=${CERT[${HOSTS[0]}]}; KEY[_]=${KEY[${HOSTS[0]}]}
MAP=$(mktemp); for n in "${!CERT[@]}"; do echo "$n ${CERT[$n]} ${KEY[$n]}"; done > "$MAP"
awk -v map="$MAP" '
    BEGIN { while ((getline l < map) > 0) { split(l, a, " "); C[a[1]] = a[2]; K[a[1]] = a[3] } }
    {
        line = $0; s = $0; sub(/#.*/, "", s)
        if (depth == 0 && s ~ /^[ \t]*server[ \t]*\{/) name = ""
        if (s ~ /^[ \t]*server_name[ \t]/) { t = s; sub(/^[ \t]*server_name[ \t]+/, "", t); sub(/[ \t;].*/, "", t); name = t }
        if (s ~ /^[ \t]*ssl_certificate[ \t]/ && (name in C)) { match(line, /^[ \t]*/); line = substr(line, 1, RLENGTH) "ssl_certificate     " C[name] ";" }
        if (s ~ /^[ \t]*ssl_certificate_key[ \t]/ && (name in K)) { match(line, /^[ \t]*/); line = substr(line, 1, RLENGTH) "ssl_certificate_key " K[name] ";" }
        o = gsub(/\{/, "{", s); c = gsub(/\}/, "}", s); depth += o - c
        print line
    }' "$NEWCONF" > "$NEWCONF.tmp" && mv "$NEWCONF.tmp" "$NEWCONF"
rm -f "$MAP"
for h in "${HOSTS[@]}"; do
    certmap "$NEWCONF" | grep -q "^$h ${CERT[$h]} ${KEY[$h]}\$" || die "could not set the certificate for $h in the new nginx file"
done
# Another enabled site may already be the default server for a port (Ubuntu's stock
# `default` site is, on 80). Only one can be, so the catch-all for that port is left out.
# grep exits non-zero when nothing matches or a glob has no files; under pipefail that would end the script.
for port in 80 443; do
    others=$({ grep -lE "^[^#]*\blisten[[:space:]]+([^;#[:space:]]*:)?$port[[:space:]][^;#]*default_server" \
                   /etc/nginx/sites-enabled/* /etc/nginx/conf.d/*.conf 2>/dev/null || true; } \
        | while read -r f; do [ "$(readlink -f "$f")" = "$(readlink -f "$LIVE")" ] || echo "$f"; done)
    if [ -n "$others" ]; then
        sed -i "/^# BEGIN default-server-$port\$/,/^# END default-server-$port\$/d" "$NEWCONF"
        note "$(echo $others) is the default server on port $port; requests there for unknown names stay with it. To have them dropped instead, disable it and run this deploy again."
    else
        ok "port $port: unknown host names and bare-IP requests will be dropped (444)"
    fi
done

# ───────────────────────────── changes ─────────────────────────────
STAGE=changed; save_state

step "Update code"
for r in api admin web; do
    [ "${OLDREV[$r]}" = "${WANT[$r]}" ] || g "$r" merge -q --ff-only "${WANT[$r]}"
    ok "$r at $(g "$r" rev-parse --short HEAD)"
done

step "Keep the current images as :pre-$PRE_TS"
for r in api admin web; do
    # The running container's image by id: the :prod tag may already point at a newer build.
    docker tag "$(docker inspect -f '{{.Image}}' "${CT[$r]}")" "${IMG[$r]%%:*}:pre-$PRE_TS"
    ok "$r: running image kept as ${IMG[$r]%%:*}:pre-$PRE_TS"
done

step "Upload limits (API .env, at least 200 MB)"
cp -p "$AENV" "${DIR[api]}/.env.bak.$TS"
awk '
    function bump(line,   key, v) {
        key = line; sub(/^[ \t]*(export[ \t]+)?/, "", key); sub(/[ \t]*=.*/, "", key)
        v = line; sub(/^[^=]*=[ \t]*/, "", v); sub(/[ \t]+#.*/, "", v); gsub(/["'\''[:space:]]/, "", v)
        return key "=" (v + 0 < 200 ? 200 : v)
    }
    /^[ \t]*(export[ \t]+)?UPLOAD_MAX_(IMAGE|DOCUMENT)_MB[ \t]*=/ { print bump($0); seen[$0 ~ /IMAGE/ ? "i" : "d"] = 1; next }
    { print }
    END { if (!seen["i"]) print "UPLOAD_MAX_IMAGE_MB=200"; if (!seen["d"]) print "UPLOAD_MAX_DOCUMENT_MB=200" }
' "${DIR[api]}/.env.bak.$TS" > "$AENV.new"
cat "$AENV.new" > "$AENV"; rm -f "$AENV.new"
ok "UPLOAD_MAX_IMAGE_MB=$(env_get "$AENV" UPLOAD_MAX_IMAGE_MB) UPLOAD_MAX_DOCUMENT_MB=$(env_get "$AENV" UPLOAD_MAX_DOCUMENT_MB)"

# Sets KEY=VALUE in an env file, replacing every existing KEY line (export/quotes included).
env_set() {
    local file=$1 key=$2 val=$3
    awk -v k="$key" -v v="$val" '
        $0 ~ "^[ \t]*(export[ \t]+)?" k "[ \t]*=" { if (!done) print k "=" v; done = 1; next }
        { print }
        END { if (!done) print k "=" v }' "$file" > "$file.new"
    cat "$file.new" > "$file"; rm -f "$file.new"
}

step "CORS: only the two sites"
env_set "$AENV" CORS_ALLOWED_ORIGINS "$WANT_CORS"
ok "CORS_ALLOWED_ORIGINS=$(env_get "$AENV" CORS_ALLOWED_ORIGINS) (was '${CORS:-<unset>}')"

case $(env_get "$WENV" INTERNAL_API_URL) in http://localhost:8080*)
    cp -p "$WENV" "${DIR[web]}/.env.bak.$TS"
    env_set "$WENV" INTERNAL_API_URL "$(env_get "$WENV" INTERNAL_API_URL | sed 's#^http://localhost:8080#http://127.0.0.1:8080#')"
    ok "INTERNAL_API_URL=$(env_get "$WENV" INTERNAL_API_URL)" ;;
esac

step "Build images (old containers keep serving)"
for r in api admin web; do
    (cd "${DIR[$r]}" && docker compose -p "${PROJ[$r]}" -f "${FILE[$r]}" build) || die "$r build failed"
    ok "$r built"
done

step "Backup tools"
command -v rsync >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq rsync >/dev/null
getent group eduplatform-backup >/dev/null || groupadd --system eduplatform-backup
install -d -m 2750 -g eduplatform-backup "$BROOT"
for s in backup restore; do install -m 750 "${DIR[api]}/deploy/backup/$s-eduplatform.sh" "/usr/local/sbin/$s-eduplatform"; done
ok "installed to /usr/local/sbin"

step "Backup (database + uploads)"
DB_NAME=$DB_NAME BACKUP_ROOT=$BROOT backup-eduplatform
BACKUP=$(sed -n 's/.* db=\([^ ]*\).*/\1/p' "$BROOT/.last-success")
[ -n "$BACKUP" ] && [ -f "$BACKUP" ] || die "backup finished but its dump could not be found"
pg_restore --list "$BACKUP" | grep -q "dbname: $DB_NAME" || die "$BACKUP does not look like a dump of $DB_NAME"
ok "$BACKUP ($(du -h "$BACKUP" | cut -f1))"
save_state

if [ "$DBVER" -lt "$TARGET_DB_VERSION" ]; then
    step "Dry run of the new migrations on a copy of the database"
    cleanup_scratch
    runuser -u postgres -- createdb --owner "$DB_USER" --template template0 "$SCRATCH_DB"
    runuser -u postgres -- pg_restore --exit-on-error --single-transaction --dbname="$SCRATCH_DB" < "$BACKUP" \
        || die "the backup did not restore into $SCRATCH_DB"
    grep -Ev '^[[:space:]]*(export[[:space:]]+)?(DATABASE_URL|SERVER_PORT|SERVER_ADDRESS|MAIL_ENABLED|STORAGE_LOCAL_DIR|SPRING_PROFILES_ACTIVE)[[:space:]]*=' "$AENV" \
        > /root/deploy-trainings-predeploy.env; chmod 600 /root/deploy-trainings-predeploy.env
    install -d -o 1001 -g 1001 "$SCRATCH_UPLOADS"
    new_img=${IMG[api]}
    docker run -d --name "$SCRATCH_CT" --network host --env-file /root/deploy-trainings-predeploy.env \
        -e SPRING_PROFILES_ACTIVE=prod -e SERVER_PORT=18080 -e SERVER_ADDRESS=127.0.0.1 -e MAIL_ENABLED=false \
        -e STORAGE_LOCAL_DIR=/opt/uploads -e DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$SCRATCH_DB" \
        -v "$SCRATCH_UPLOADS:/opt/uploads" "$new_img" >/dev/null
    wait_ready "migrations on the copy" http://127.0.0.1:18080/actuator/health/readiness 600 "$SCRATCH_CT" \
        || die "the new release did not start on a copy of the database; production is untouched"
    v=$(psqlq "$SCRATCH_DB" "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+$'")
    [ "$v" = "$TARGET_DB_VERSION" ] || die "the copy reached V$v, expected V$TARGET_DB_VERSION"
    ok "copy migrated to V$v"
    cleanup_scratch
fi

# ───────────────────────────── swap ─────────────────────────────
STAGE=swapped
step "API"
SWAPPED+=(api); save_state
(cd "${DIR[api]}" && docker compose -p "${PROJ[api]}" -f "${FILE[api]}" up -d --no-build)
wait_ready API http://127.0.0.1:8080/actuator/health/readiness 600 "${CT[api]}" || die "API did not come up"

step "Dashboard"
SWAPPED+=(admin); save_state
(cd "${DIR[admin]}" && docker compose -p "${PROJ[admin]}" -f "${FILE[admin]}" up -d --no-build)
wait_ready dashboard http://127.0.0.1:8081/healthz 120 "${CT[admin]}" || die "dashboard did not come up"

step "Public site"
SWAPPED+=(web); save_state
(cd "${DIR[web]}" && docker compose -p "${PROJ[web]}" -f "${FILE[web]}" up -d --no-build)
wait_ready "public site" http://127.0.0.1:3000/api/health 180 "${CT[web]}" || die "public site did not come up"

through_nginx() { # host path -> status
    curl -s -o /dev/null -w '%{http_code}' --max-time 15 --resolve "$1:443:127.0.0.1" "https://$1$2"
}

step "nginx (the file prepared during the checks)"
NGINX_BAK=$LIVE.bak.$TS; cp -p "$LIVE" "$NGINX_BAK"; save_state
install -m 644 "$NEWCONF" "$LIVE"
restore_nginx() { cp -p "$NGINX_BAK" "$LIVE"; nginx -t >/dev/null 2>&1 && systemctl reload nginx; }
if ! out=$(nginx -t 2>&1); then echo "$out" | sed 's/^/      /'; restore_nginx; die "new nginx file rejected; the old one is back"; fi
if echo "$out" | grep -q 'conflicting server name'; then
    echo "$out" | sed 's/^/      /'; restore_nginx; die "another enabled site also serves these names; the old file is back"
fi
systemctl reload nginx
sleep 2
nfail=0
chk() { local got; got=$(through_nginx "$1" "$2"); case " $3 " in *" $got "*) ok "$1$2 → $got" ;; *) bad "$1$2 → $got (want $3)"; nfail=1 ;; esac; }
chk trainings.aztu.edu.az /az "200 307 308"
chk dashboard-trainings.aztu.edu.az / "200"
chk api-trainings.aztu.edu.az "/api/public/courses?size=1" "200"
chk api-trainings.aztu.edu.az /actuator/health "403"
chk dashboard-trainings.aztu.edu.az /.env "404"
chk trainings.aztu.edu.az /.git/config "404"
if grep -q '^# BEGIN default-server-443' "$LIVE"; then
    got=$(curl -sk -o /dev/null -w '%{http_code}' --max-time 10 -H 'Host: not-a-trainings-host' https://127.0.0.1/ || true)
    [ "$got" = 000 ] && ok "unknown Host over https is dropped" || { bad "unknown Host over https → $got (want a closed connection)"; nfail=1; }
fi
for h in dashboard-trainings.aztu.edu.az api-trainings.aztu.edu.az; do
    got=$(head -c 2097152 /dev/zero | curl -s -o /dev/null -w '%{http_code}' --max-time 30 --resolve "$h:443:127.0.0.1" \
        -X POST -H 'Content-Type: application/octet-stream' --data-binary @- "https://$h/api/media")
    case $got in 401|403) ok "2 MB upload to $h reaches the API ($got)" ;; *) bad "2 MB upload to $h → $got"; nfail=1 ;; esac
done
if [ "$nfail" = 1 ]; then restore_nginx; die "checks through the new nginx file failed; the old one is back"; fi
NGINX_BAK=""; save_state

# ───────────────────────────── smoke checks ─────────────────────────────
step "Smoke checks"
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }
[ "$(code http://127.0.0.1:8080/api/public/tutors?size=1)" = 200 ] && ok "expert directory" || bad "GET /api/public/tutors"
[ "$(code 'http://127.0.0.1:8080/api/public/courses?type=ONE_TIME&size=1')" = 200 ] && ok "one-time courses filter" || bad "ONE_TIME filter"
for p in /api/admin/calendar /api/portal/room-bookings/series/mine; do
    c=$(code "http://127.0.0.1:8080$p"); case $c in 401|403) ok "$p exists and needs a login ($c)" ;; *) bad "$p → $c" ;; esac
done
v=$(psqlq "$DB_NAME" "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+$'")
[ "$v" = "$TARGET_DB_VERSION" ] && ok "database at V$v" || bad "database at V$v, expected V$TARGET_DB_VERSION"
envs=$(docker exec "${CT[api]}" env)
for k in UPLOAD_MAX_IMAGE_MB UPLOAD_MAX_DOCUMENT_MB; do
    val=$(echo "$envs" | sed -n "s/^$k=//p"); [ "${val:-0}" -ge 200 ] && ok "$k=$val" || bad "$k=${val:-unset}"
done
c=$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: GET' http://127.0.0.1:8080/api/public/courses)
[ "$c" = 403 ] && ok "CORS refuses http://localhost:3000" || bad "CORS preflight from localhost → $c (want 403)"
for p in 8080 3000 8081; do
    addrs=$(ss -ltnH "( sport = :$p )" | awk '{print $4}' | sort -u | tr '\n' ' ')
    case $addrs in
        '') bad "nothing listens on :$p" ;;
        *) if echo "$addrs" | tr ' ' '\n' | grep -v '^$' | grep -qv '^127\.0\.0\.1:'; then bad ":$p listens on $addrs (want 127.0.0.1 only)"; else ok ":$p on loopback only ($addrs)"; fi ;;
    esac
done

[ "$FAILS" = 0 ] || die "$FAILS check(s) failed after the swap"

STAGE=done
rm -f "$STATE"
docker image prune -f >/dev/null 2>&1 || true
step "Done"
for r in api admin web; do echo "   $r: $(g "$r" rev-parse --short HEAD)"; done
echo "   backup: $BACKUP"
echo "   previous images kept as :pre-$PRE_TS (docker image ls | grep pre-$PRE_TS)"
echo "   log: $LOG"
