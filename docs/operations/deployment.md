# Deploying the AzTU trainings platform

The cross-repo runbook: what to do, in what order, on a fresh Ubuntu host. Each
repo also has its own `DEPLOY.md` with the detail for that one service — this
document is the order of operations between them, and the things that only go
wrong when the three are combined.

Read [DB_SETUP.md](../../DB_SETUP.md) alongside step 2; it is the authority on
PostgreSQL, tuning and backups.

## What you are deploying

| Service | Repo | Listens on | Public hostname |
| --- | --- | --- | --- |
| Backend API | `api-trainings.aztu.edu.az` | `127.0.0.1:8080` | `api-trainings.aztu.edu.az` |
| Public site | `trainings.aztu.edu.az` | `127.0.0.1:3000` | `trainings.aztu.edu.az` |
| Admin portal | `admin-trainings.aztu.edu.az` | `127.0.0.1:8081` | `dashboard-trainings.aztu.edu.az` |

The admin portal's repo is named `admin-trainings` but it is **served at
`dashboard-trainings.aztu.edu.az`**. Anything that names a host — CSP, CORS,
`NEXT_PUBLIC_PORTAL_URL`, nginx `server_name` — must use `dashboard-trainings`.

Every service runs from its repo's `docker-compose.prod.yml`, and the ports above
are the ones those files use. The admin repo's plain `docker-compose.yml` is for
trying the image on a workstation, not for this server. Under it the portal's own
`/api` and `/ws` proxy points at the container's loopback, where no API runs, and
its port used to be published on every interface, where Docker's rules put it
ahead of UFW. If the portal is still running that way (it once ran on 3001),
move it:

```bash
cd /opt/trainings/admin-trainings.aztu.edu.az
docker compose down                                   # the bridged container
docker compose -f docker-compose.prod.yml up -d --build
curl -fsS http://127.0.0.1:8081/healthz               # ok
```

then make sure the host vhost proxies `dashboard-trainings` to `127.0.0.1:8081`
(§6). The prod build uses `VITE_API_BASE_URL=/api`, so the portal calls the API
same-origin, unless a `VITE_API_BASE_URL` in the shell or in a `.env` next to the
compose file overrides it; remove such an override.

All three prod compose files use `network_mode: host`, so each binds a host port
directly over **plain HTTP**. No container terminates TLS. The host nginx does
([deploy/nginx/trainings.conf](../../deploy/nginx/trainings.conf), §6), and it is
the only thing that should be reachable from the internet.

Request paths worth knowing, because they explain the nginx config in step 6:

```
browser ──https──▶ host nginx :443 ──▶ :3000  public site (Next.js)
                                        └──▶ :8080  API, server-side (INTERNAL_API_URL, loopback)
browser ──https──▶ host nginx :443 ──▶ :8080  API, directly (NEXT_PUBLIC_API_URL + wss)
browser ──https──▶ host nginx :443 ──▶ :8081  admin portal (its own nginx)
                                        └──▶ :8080  API, proxied same-origin as /api/ and /ws
```

The admin portal calls the API through **its own** nginx at `/api/`, which is why
it needs no CORS entry. The public site calls the API **cross-origin** from the
browser, which is why `CORS_ALLOWED_ORIGINS` must list it.

---

## 1. Host prerequisites

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 nginx certbot python3-certbot-nginx rsync
sudo systemctl enable --now docker nginx
```

PostgreSQL is not in that list on purpose. Ubuntu 22.04's `postgresql` package is
version 14, while the rest of this runbook and DB_SETUP.md assume 16 (paths
under `/etc/postgresql/16`, and the version Flyway supports). Install 16 from the
PostgreSQL project's repository with [DB_SETUP.md §1](../../DB_SETUP.md#1-install-postgresql-16).

Point all three DNS records at this host before step 6: certbot validates over
HTTP and fails otherwise.

## 2. Database

Full detail in [DB_SETUP.md](../../DB_SETUP.md). The minimum:

```bash
sudo -u postgres createuser --pwprompt eduplatform      # type the password from api/.env
sudo -u postgres createdb --owner=eduplatform --encoding=UTF8 eduplatform
```

`--pwprompt` asks for the password instead of taking it on the command line,
where it would land in the shell history and, while it runs, in the process list
of everyone on the host. The password must match `DATABASE_PASSWORD` in
`api-trainings.aztu.edu.az/.env`. `--owner` matters: since PostgreSQL 15 only the
owner can create tables in `public`, and Flyway fails on its first migration
without it. Do **not** create any tables: Flyway owns the schema and creates it on
first boot.

## 3. Upload directory

```bash
sudo mkdir -p /opt/uploads
sudo chown -R 1001:1001 /opt/uploads
```

`1001:1001` is the container's `app` user. Get this wrong and uploads fail with
`EACCES`. The path must equal `STORAGE_LOCAL_DIR` in the API's `.env` and the
compose mount — both are already `/opt/uploads`.

This directory is the media store; it is **not** in `pg_dump`. See the backup
section of DB_SETUP.md.

## 4. Firewall

Do this **before** starting the containers, not after.

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw deny 8080/tcp
sudo ufw deny 3000/tcp
sudo ufw deny 8081/tcp
sudo ufw enable
```

Closing 8080 is a security control, not tidiness. The API believes
`X-Forwarded-For` from any peer with a loopback or private address, because it
treats those as its own proxies (Tomcat's `RemoteIpValve`; see
[Client IP](../../DEPLOY.md#client-ip) in the API's DEPLOY.md). On a host with a
private address — as a campus server usually has — any machine on the same
network that could reach 8080 would therefore be believed: it could name any
client IP it liked and walk past the rate limiter, the IP blocklist and audit
attribution in one request. Anyone reaching 8080 would also skip TLS and the
vhost's `/actuator` restriction from step 6.

`ufw deny` works here **because** the compose files use host networking. A
published Docker port (`ports:`) would insert its own rules ahead of UFW's INPUT
chain and stay reachable despite the deny.

## 5. Clone, place `.env`, and start the API

```bash
sudo mkdir -p /opt/trainings && cd /opt/trainings
git clone <api repo>   api-trainings.aztu.edu.az
git clone <web repo>   trainings.aztu.edu.az
git clone <admin repo> admin-trainings.aztu.edu.az
```

Copy the three prepared `.env` files in. They are gitignored, so they do not
arrive with the clone:

| File | Contains |
| --- | --- |
| `api-trainings.aztu.edu.az/.env` | DB credentials, `JWT_ACCESS_SECRET`, mail, storage, flags |
| `trainings.aztu.edu.az/.env` | `NEXT_PUBLIC_*` build args. `INTERNAL_API_URL` is set in its `docker-compose.prod.yml` to `http://127.0.0.1:8080`, and a value in `.env` cannot override it |
| `admin-trainings.aztu.edu.az/` | nothing — `.env.production` is committed and has no secrets |

Start the API first. The public site's build pre-renders the landing and category
pages, so bringing it up in this order means those pages ship with real content
instead of empty fallbacks:

```bash
cd /opt/trainings/api-trainings.aztu.edu.az
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f    # watch Flyway apply every migration
```

Wait for healthy before continuing:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

Every migration means one per file in `src/main/resources/db/migration`: V1 to
V5, then V8 up to the highest. V6 or V7 in the log means the dev seed accounts
were applied; see
[DB_SETUP.md §7](../../DB_SETUP.md#7-first-boot) before going on. The compose file
pins `SPRING_PROFILES_ACTIVE=prod`, so a `dev` in `.env` can no longer cause it.

If it refuses to start, read the message — `StartupSecurityValidator` fails
deliberately and says exactly which setting is wrong (missing DSN, placeholder
JWT secret, dev seed migrations on the Flyway path).

Check `CORS_ALLOWED_ORIGINS` in `.env` now. It must be exactly
`https://trainings.aztu.edu.az,https://dashboard-trainings.aztu.edu.az`: never `*`
and never a wildcard. The API allows credentials on CORS, so every origin it
accepts can call `POST /api/auth/refresh` with an admin's refresh cookie and read
the new token. The API now refuses to start on a wildcard, `null` or plain-http
origin, so a bad value shows up here as a startup error rather than as an open
door.

## 6. TLS and the host reverse proxy

One nginx on the host terminates TLS for all three hostnames. Its whole config is
[deploy/nginx/trainings.conf](../../deploy/nginx/trainings.conf), which is
installed as `/etc/nginx/sites-available/trainings`. Read the comments in it; in
short, it:

- redirects port 80 to HTTPS, and serves HTTP/2 on 443;
- **dashboard-trainings**: allows 550 MB request bodies with streaming, and
  passes the WebSocket upgrade through to the portal container, which forwards
  `/api/` and `/ws` to the API. With nginx's defaults, every cover over 1 MB and
  every document and video got a 413 here, and the notification socket got a 400.
  It also sends HSTS for the portal;
- **api-trainings**: allows 550 MB bodies, passes the upgrade on `/ws`, refuses
  `/actuator` to anything but the host itself, and adds the headers Spring does
  not send (CSP with `sandbox`, `Referrer-Policy`, `Permissions-Policy`,
  `Cross-Origin-Resource-Policy: same-site`), because media is served from this
  host;
- turns `server_tokens` off, so neither responses nor error pages name the nginx
  version and OS.

The order below matters. The file names certificate paths, and `nginx -t` fails
while they do not exist, so get the certificate first, from the stock config, and
only then enable the file.

```bash
# 1. Current nginx. Ubuntu ships 1.18 with HTTP/2 fixes backported, and HTTP/2
#    Rapid Reset (CVE-2023-44487) is held off by its defaults
#    (keepalive_requests 1000, http2_max_concurrent_streams 128), which
#    trainings.conf leaves alone.
sudo apt update && sudo apt install --only-upgrade -y nginx

# 2. No version or OS in the Server header anywhere on the host, the default
#    site included (trainings.conf also sets it for its own servers).
sudo sed -i 's/^\(\s*\)# *server_tokens off;/\1server_tokens off;/' /etc/nginx/nginx.conf
grep -n 'server_tokens' /etc/nginx/nginx.conf       # expect an uncommented `server_tokens off;`

# 3. One certificate for the three names, answered on port 80 by the stock default
#    site. certonly: certbot must not edit the config; the file below already has
#    the TLS lines. The deploy hook makes nginx load each renewed certificate.
sudo certbot certonly --nginx --cert-name trainings.aztu.edu.az \
  -d trainings.aztu.edu.az -d dashboard-trainings.aztu.edu.az -d api-trainings.aztu.edu.az \
  --deploy-hook 'systemctl reload nginx'

# 4. Enable the vhost.
sudo cp /opt/trainings/api-trainings.aztu.edu.az/deploy/nginx/trainings.conf /etc/nginx/sites-available/trainings
sudo ln -sf /etc/nginx/sites-available/trainings /etc/nginx/sites-enabled/trainings
sudo nginx -t && sudo systemctl reload nginx

# 5. Renewal still works through the new config.
sudo certbot renew --dry-run
```

**On a server that already has a vhost**, typically written by `certbot --nginx`
with `listen 443 ssl; # managed by Certbot` lines, do not run step 3. Check the
certificate's name and paths with `sudo certbot certificates`; if they differ from
`/etc/letsencrypt/live/trainings.aztu.edu.az/`, change the six `ssl_certificate*`
lines in the copy. Then keep a backup of the old file and do step 4:

```bash
sudo cp /etc/nginx/sites-available/trainings /root/trainings.nginx.$(date +%F)
diff -u /root/trainings.nginx.$(date +%F) /opt/trainings/api-trainings.aztu.edu.az/deploy/nginx/trainings.conf
```

`ls -l /etc/nginx/sites-enabled/` should then list `trainings` and at most
`default`. Any other file naming one of the three hosts (certbot sometimes writes
its own) makes `nginx -t` warn `conflicting server name ... ignored`, and nginx
then serves one of the two blocks, not necessarily this one. Remove the other link.

`/actuator` is refused from outside after this. Check first that no uptime
monitor probes it from elsewhere; if one does, add `allow <its address>;` above
`deny all;` in the copy, or point it at the host's own
`http://127.0.0.1:8080/actuator/health/readiness`.

How the API turns these headers into a client IP — and the one case, on-campus
clients with private addresses, where a forged entry can still get through — is
under "Client IP" in the API's [DEPLOY.md](../../DEPLOY.md#client-ip).

**TLS is not optional here.** The portal's refresh-token cookie is `Secure`, so
over plain HTTP the browser discards it: sign-in appears to work, then every admin
is logged out 15 minutes later, repeatedly, with nothing useful in the logs. If you
must run without TLS temporarily, set `COOKIE_SECURE=false` in the API's `.env` —
and treat it as a stopgap, because the refresh token then travels in clear text.

## 7. Start the two frontends

```bash
cd /opt/trainings/trainings.aztu.edu.az
docker compose -f docker-compose.prod.yml up -d --build

cd /opt/trainings/admin-trainings.aztu.edu.az
docker compose -f docker-compose.prod.yml up -d --build
```

Both bake `NEXT_PUBLIC_*` / `VITE_*` into their browser bundles at build time, so
changing any of those values later needs `up -d --build`, never just a restart.

The public site's compose file sets `INTERNAL_API_URL=http://127.0.0.1:8080`
itself, so its server-side renders and its auth BFF reach the API over loopback
whatever `.env` says. An older prepared `.env` pointed it at
`https://api-trainings.aztu.edu.az`. That only works if this host can reach its
own public address (hairpin NAT), and it puts every visitor's server-side calls in
one API rate-limit bucket. Check:

```bash
docker exec eduplatform-frontend printenv INTERNAL_API_URL          # http://127.0.0.1:8080
docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' eduplatform-frontend
# expect .../trainings.aztu.edu.az/docker-compose.prod.yml
```

## 8. Create the first admin

Nothing can be administered until this exists, and it is the one step that needs
either working mail or a log-reading trick.

```bash
cd /opt/trainings/api-trainings.aztu.edu.az
sed -i 's/^ADMIN_SELF_REGISTER=false/ADMIN_SELF_REGISTER=true/' .env
docker compose -f docker-compose.prod.yml up -d
```

Register at `https://dashboard-trainings.aztu.edu.az`, which sends an OTP. **With
`MAIL_ENABLED=false` no mail is sent — the OTP is written to the log instead:**

```bash
docker compose -f docker-compose.prod.yml logs --tail=200 | grep 'mail:disabled'
```

Finish the registration with that code, then close the door and recreate the
container (`up -d`, not `restart`: `.env` is read only when the container is
created):

```bash
sed -i 's/^ADMIN_SELF_REGISTER=true/ADMIN_SELF_REGISTER=false/' .env
docker compose -f docker-compose.prod.yml up -d
```

The API accepts an admin self-registration only while **both** hold:
`ADMIN_SELF_REGISTER=true` and no `ADMIN`/`SUPER_ADMIN` account exists yet.
Anything else is `403 ADMIN_REGISTER_CLOSED`. So the zero-admins check is the
backstop — once your account exists, a flag left on by mistake cannot mint a second
admin — and setting the flag back is what closes the door outright. Until your
account exists, nothing but the flag stands between the endpoint and whoever
reaches it first, so run the three steps back to back.

### Promote the first SUPER_ADMIN

The bootstrap account is an `ADMIN`. Only a `SUPER_ADMIN` can grant or revoke
`SUPER_ADMIN` through the API (`403 ROLE_ESCALATION_FORBIDDEN`), and nobody can
change their own roles (`403 SELF_ROLE_CHANGE_FORBIDDEN`), so the first one has to
be granted in the database, once:

```bash
sudo -u postgres psql -d eduplatform <<'SQL'
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON r.code = 'SUPER_ADMIN'
WHERE u.email = 'you@aztu.edu.az'
ON CONFLICT DO NOTHING;
SQL
```

`INSERT 0 1` means it worked; `INSERT 0 0` means the email did not match (or the
account already had the role). Roles travel in the access token, so sign out and
back in to pick it up. Any further `SUPER_ADMIN` is then granted from the admin
portal by this one.

### Turn mail on

Only now, because the log trick above works only while mail is off. With mail off,
expert sign-up, e-mail verification and password reset never reach the user, and
their codes and links sit in the container log. In the API's `.env`, set
`MAIL_USERNAME` and `MAIL_PASSWORD` for the sending mailbox (the AzTU relay, or a
Gmail app password for `MAIL_FROM`) and `MAIL_ENABLED=true`. Then:

```bash
timeout 5 bash -c '</dev/tcp/smtp.gmail.com/587' && echo open || echo BLOCKED   # egress to MAIL_HOST
docker compose -f docker-compose.prod.yml up -d
```

Request a password reset for your own account from the public site, and confirm
the mail arrives and the link works. Nothing else proves it: a failed send is
logged, not shown to the user.

## 9. Verify

Check behaviour, not just that containers are up.

```bash
# Health. The API's actuator is loopback-only (step 6), so check it on the host
# itself; this is also the URL to give an uptime monitor running there.
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
curl -fsS https://trainings.aztu.edu.az/api/health
curl -fsS https://dashboard-trainings.aztu.edu.az/healthz

# Public catalogue, with filters applied server-side
curl -fsS 'https://api-trainings.aztu.edu.az/api/public/courses?type=ONLINE&level=BEGINNER&size=5'

# Swagger and actuator must NOT be public. Run these from a machine other than
# the host. A 200 for health or a 404 for prometheus means the vhost's /actuator
# location is missing — only the app's exposure list is keeping prometheus closed.
curl -o /dev/null -w '%{http_code}\n' https://api-trainings.aztu.edu.az/swagger-ui.html   # expect 404
curl -o /dev/null -w '%{http_code}\n' https://api-trainings.aztu.edu.az/actuator/health     # expect 403
curl -o /dev/null -w '%{http_code}\n' https://api-trainings.aztu.edu.az/actuator/prometheus # expect 403

# Security headers on the public site
curl -sI https://trainings.aztu.edu.az | grep -iE 'content-security-policy|strict-transport'

# Host nginx (§6). HTTP/2, no version in Server, HSTS on the portal itself, the
# API's own headers.
curl -s -o /dev/null -w '%{http_version}\n' https://trainings.aztu.edu.az/            # 2
curl -sI https://api-trainings.aztu.edu.az/ | grep -i '^server'                     # server: nginx
curl -sI https://dashboard-trainings.aztu.edu.az/ | grep -ic '^strict-transport'     # 1
curl -sI https://api-trainings.aztu.edu.az/api/public/courses \
  | grep -iE '^(content-security-policy|referrer-policy|permissions-policy|cross-origin-resource-policy)'  # all four

# Request bodies over 1 MB reach the API through BOTH vhosts: 401 from the API,
# not 413 from nginx.
for h in dashboard-trainings api-trainings; do
  head -c 2000000 /dev/zero | curl -s -o /dev/null -w "$h: %{http_code}\n" -X POST \
    -H 'Content-Type: application/octet-stream' --data-binary @- https://$h.aztu.edu.az/api/media
done

# The notification socket upgrades on both hosts: 101 (curl then waits; the
# timeout ends it).
for h in dashboard-trainings api-trainings; do
  curl -s -o /dev/null -w "$h /ws: %{http_code}\n" --http1.1 --max-time 5 \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H "Origin: https://$h.aztu.edu.az" \
    https://$h.aztu.edu.az/ws
done

# CORS: only the two real origins. A foreign one gets 403 and no
# Access-Control-Allow-Origin; the portal's gets 200 with it.
for o in https://evil.aztu.edu.az https://dashboard-trainings.aztu.edu.az; do
  curl -s -D - -o /dev/null -X OPTIONS https://api-trainings.aztu.edu.az/api/auth/refresh \
    -H "Origin: $o" -H 'Access-Control-Request-Method: POST' \
    | grep -iE '^HTTP|^access-control-allow-origin'
done

# Rate limiter: the 11th login in a minute should be 429 with Retry-After
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "$i:%{http_code} " -X POST \
    https://api-trainings.aztu.edu.az/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"nobody@example.com","password":"wrong"}'
done; echo

# The port that must not be reachable from outside
curl --max-time 5 http://api-trainings.aztu.edu.az:8080/actuator/health   # expect a timeout

# On the host: the public site talks to the API over loopback, and backups run
docker exec eduplatform-frontend printenv INTERNAL_API_URL   # http://127.0.0.1:8080
sudo backup-eduplatform --check                              # OK: ...
```

Then in a browser, as the admin you just created:

- sign in, wait past the 15-minute access-token expiry, and confirm you are
  **still** signed in — this is the cookie-refresh path, and the thing TLS
  misconfiguration breaks
- create a course, upload a cover image, upload a lesson video over 100 MB;
  reload the course and confirm the cover is still set — an upload that
  succeeds is not proof the course kept it
- confirm an `.svg` and a `.js` are refused by the file picker
- open the course list and the approvals page with no search term, then search
  your own courses from the header and from the course list
- as a student on the public site, register, enrol in a free course, open a lesson

## 10. Redeploy

```bash
cd /opt/trainings/<repo>
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

Deploy in the order API, admin portal, public site. Flyway applies any new
migrations when the API starts. Before a release that adds a migration, take a
backup first (`sudo backup-eduplatform`): Flyway cannot roll back (§11).

There is one API container and no rolling deploy, so the API is unavailable from
the moment the old container stops until the new one is ready. Requests already
running when it stops get up to 25 seconds to finish (`stop_grace_period: 30s`
in its compose file lets that happen; Docker's default would kill it at 10).

After editing a `.env`, run `docker compose -f docker-compose.prod.yml up -d`:
compose sees the changed configuration and recreates the container.
`docker compose restart` does **not** re-read `.env`, so the old values stay.
Anything `NEXT_PUBLIC_*` or `VITE_*` also needs `--build`, because it is inside
the bundle.

Two things live outside the containers, and `git pull` updates neither:

```bash
# The host nginx config (§6). No output means the live file matches the repo.
diff -u /etc/nginx/sites-available/trainings /opt/trainings/api-trainings.aztu.edu.az/deploy/nginx/trainings.conf
# If it differs because the repo changed: copy it over (keeping your
# ssl_certificate paths if they differ), then
sudo nginx -t && sudo systemctl reload nginx

# The backup scripts (DB_SETUP.md §9), when deploy/backup/ changed.
cd /opt/trainings/api-trainings.aztu.edu.az
sudo install -o root -g root -m 0755 deploy/backup/backup-eduplatform.sh  /usr/local/sbin/backup-eduplatform
sudo install -o root -g root -m 0755 deploy/backup/restore-eduplatform.sh /usr/local/sbin/restore-eduplatform
```

## 11. Rollback

```bash
cd /opt/trainings/<repo>
git log --oneline -5
git checkout <previous-sha>
docker compose -f docker-compose.prod.yml up -d --build
```

**Flyway does not roll back.** A release containing a migration is forward-only:
checking the code out at an older commit leaves the newer schema in place. If a
migration is the problem, check out the old code, then restore the database from
the backup taken just before the deploy:

```bash
sudo restore-eduplatform --db /var/backups/eduplatform/db/eduplatform-<stamp>.dump
```

It stops the backend, keeps the current database under another name, restores,
and starts the backend (built from the old code) again. See
[DB_SETUP.md §9](../../DB_SETUP.md#restore). This is why the backup must come
*before* any release that adds a migration.

## Pre-launch checklist

- [ ] `DATABASE_PASSWORD` in `api/.env` matches the real Postgres role
- [ ] `JWT_ACCESS_SECRET` is the generated value, not a placeholder
- [ ] The API logs `The following 1 profile is active: "prod"`, and Flyway
      history has no version 6 or 7
- [ ] `CORS_ALLOWED_ORIGINS` is exactly
      `https://trainings.aztu.edu.az,https://dashboard-trainings.aztu.edu.az`
      (never `*`); a preflight from another origin gets 403
- [ ] No `Outgoing mail is OFF` warning in the API's startup log (after §8)
- [ ] Every service runs from its `docker-compose.prod.yml` (none from the
      bridged `docker-compose.yml`)
- [ ] `docker exec eduplatform-frontend printenv INTERNAL_API_URL` is
      `http://127.0.0.1:8080`
- [ ] `/opt/uploads` exists and is owned by `1001:1001`
- [ ] UFW denies 8080, 3000 and 8081; allows 22, 80, 443
- [ ] TLS live on all three hostnames, HTTP redirects to HTTPS
- [ ] `/etc/nginx/sites-available/trainings` matches `deploy/nginx/trainings.conf`
      (`diff -u`), and `sudo certbot renew --dry-run` passes
- [ ] Host nginx sets `X-Real-IP`, `X-Forwarded-For` and `X-Forwarded-Proto`
- [ ] `client_max_body_size 550m` on **both** the dashboard and the API vhost
      (a 2 MB POST through either gets 401, not 413)
- [ ] `/ws` returns 101 on both the dashboard and the API host
- [ ] API vhost refuses `/actuator` from anywhere but `127.0.0.1` (403 from outside)
- [ ] HTTP/2 negotiated on all three hosts; `Server: nginx` without a version
- [ ] HSTS on the dashboard's own pages; CSP, `Referrer-Policy`,
      `Permissions-Policy` and `Cross-Origin-Resource-Policy` on api-trainings
- [ ] Uptime monitors use `/actuator/health/readiness`, not the aggregate
      `/actuator/health`
- [ ] First admin created, `ADMIN_SELF_REGISTER=false` again, and promoted to
      `SUPER_ADMIN` (§8)
- [ ] Then SMTP filled in, `MAIL_ENABLED=true`, and one real password reset
      received (otherwise password reset and email verification silently do
      nothing for real users)
- [ ] `SWAGGER_ENABLED=false`, `/actuator/prometheus` not publicly reachable
- [ ] Backups installed (DB_SETUP.md §9): `sudo backup-eduplatform --check` is OK,
      the off-host pull has run, and the restore has been drilled once
- [ ] `PAYMENTS_ENABLED=false` while no payment provider is integrated
