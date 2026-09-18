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
`FRONTEND_PORTAL_URL`, `NEXT_PUBLIC_PORTAL_URL`, nginx `server_name` — must use
`dashboard-trainings`.

The port above is the host-networked prod compose file. The portal can also run
under the bridged `docker-compose.yml` with `PORT` set (the live server uses
3001). That works, but in that mode the container's own `/api` proxy cannot
reach the host's API, so the portal must be built with an absolute
`VITE_API_BASE_URL=https://api-trainings.aztu.edu.az/api` and calls the API
cross-origin. Its CSP allows both.

All three prod compose files use `network_mode: host`, so each binds a host port
directly over **plain HTTP**. Nothing in any repo terminates TLS. A reverse proxy
on the host does that and is the only thing that should be reachable from the
internet.

Request paths worth knowing, because they explain the nginx config in step 5:

```
browser ──https──▶ host nginx :443 ──▶ :3000  public site (Next.js)
                                        └──▶ :8080  API, server-side (INTERNAL_API_URL, loopback)
browser ──https──▶ host nginx :443 ──▶ :8080  API, directly (NEXT_PUBLIC_API_URL + wss)
browser ──https──▶ host nginx :443 ──▶ :8081  admin portal (its own nginx)
                                        └──▶ :8080  API, proxied same-origin as /api/
```

The admin portal calls the API through **its own** nginx at `/api/`, which is why
it needs no CORS entry. The public site calls the API **cross-origin** from the
browser, which is why `CORS_ALLOWED_ORIGINS` must list it.

---

## 1. Host prerequisites

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 nginx postgresql certbot python3-certbot-nginx
sudo systemctl enable --now docker postgresql nginx
```

Point all three DNS records at this host before step 5 — certbot validates over
HTTP and will fail otherwise.

## 2. Database

Full detail in [DB_SETUP.md](../../DB_SETUP.md). The minimum:

```bash
sudo -u postgres psql -c "CREATE ROLE eduplatform LOGIN PASSWORD '<the password from api/.env>';"
sudo -u postgres psql -c "CREATE DATABASE eduplatform OWNER eduplatform;"
```

The password must match `DATABASE_PASSWORD` in `api-trainings.aztu.edu.az/.env`.
Do **not** create any tables: Flyway owns the schema and creates it on first boot.

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

Closing 8080 is a security control, not tidiness. The API runs with
`TRUST_FORWARD_HEADERS=true`, so it believes the `X-Forwarded-For` it is given.
If 8080 is reachable directly, any caller can forge that header and walk past the
rate limiter, the IP blocklist and audit attribution in one request.

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
| `trainings.aztu.edu.az/.env` | `NEXT_PUBLIC_*` build args, `REVALIDATE_SECRET` |
| `admin-trainings.aztu.edu.az/` | nothing — `.env.production` is committed and has no secrets |

Start the API first. The public site's build pre-renders the landing and category
pages, so bringing it up in this order means those pages ship with real content
instead of empty fallbacks:

```bash
cd /opt/trainings/api-trainings.aztu.edu.az
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f    # watch Flyway apply V1..V11
```

Wait for healthy before continuing:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

If it refuses to start, read the message — `StartupSecurityValidator` fails
deliberately and says exactly which setting is wrong (missing DSN, placeholder
JWT secret, dev seed migrations on the Flyway path).

## 6. TLS and the host reverse proxy

Create one vhost per hostname. `/etc/nginx/sites-available/trainings`:

```nginx
# Shared by all three: the forwarded headers the services depend on.
# X-Real-IP is $remote_addr, which a client cannot forge. The public site's BFF
# prefers it precisely for that reason — without it every visitor's auth calls
# share a single rate-limit bucket, and the 11th login site-wide in a minute
# starts returning 429.
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;

server {
    listen 443 ssl http2;
    server_name trainings.aztu.edu.az;
    location / { proxy_pass http://127.0.0.1:3000; }
}

server {
    listen 443 ssl http2;
    server_name dashboard-trainings.aztu.edu.az;
    # The container's own nginx handles /api/ and /ws internally, so everything
    # goes to one upstream. It rewrites $remote_addr from the X-Forwarded-For set
    # above (set_real_ip_from 127.0.0.1), which is why this proxy must run on
    # loopback — if TLS ever moves to another machine, add that machine's address
    # to set_real_ip_from in the admin repo's nginx/nginx.conf, or the API will
    # rate-limit every portal user as one client.
    location / { proxy_pass http://127.0.0.1:8081; }
}

server {
    listen 443 ssl http2;
    server_name api-trainings.aztu.edu.az;

    # 550m, matching the admin portal's own limit: lesson video is uploaded as a
    # single PUT up to UPLOAD_MAX_VIDEO_MB (512). A smaller value here fails large
    # uploads with a bodyless 413 the uploader cannot explain.
    client_max_body_size 550m;
    proxy_read_timeout   300s;
    proxy_send_timeout   300s;

    location / { proxy_pass http://127.0.0.1:8080; }

    # STOMP notifications. The endpoint is exactly /ws — this must stay in step
    # with NEXT_PUBLIC_WS_URL in the public site's .env.
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/trainings /etc/nginx/sites-enabled/
sudo nginx -t
sudo certbot --nginx -d trainings.aztu.edu.az -d dashboard-trainings.aztu.edu.az -d api-trainings.aztu.edu.az
sudo systemctl reload nginx
```

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

Finish the registration with that code, then close the door and restart:

```bash
sed -i 's/^ADMIN_SELF_REGISTER=true/ADMIN_SELF_REGISTER=false/' .env
docker compose -f docker-compose.prod.yml up -d
```

Self-registration is refused automatically once any `ADMIN`/`SUPER_ADMIN` exists,
so this is belt and braces rather than the only guard.

## 9. Verify

Check behaviour, not just that containers are up.

```bash
# Health
curl -fsS https://api-trainings.aztu.edu.az/actuator/health/readiness
curl -fsS https://trainings.aztu.edu.az/api/health
curl -fsS https://dashboard-trainings.aztu.edu.az/healthz

# Public catalogue, with filters applied server-side
curl -fsS 'https://api-trainings.aztu.edu.az/api/public/courses?type=ONLINE&level=BEGINNER&size=5'

# Swagger and metrics must NOT be public
curl -o /dev/null -w '%{http_code}\n' https://api-trainings.aztu.edu.az/swagger-ui.html   # expect 404
curl -o /dev/null -w '%{http_code}\n' https://api-trainings.aztu.edu.az/actuator/prometheus # expect 404

# Security headers on the public site
curl -sI https://trainings.aztu.edu.az | grep -iE 'content-security-policy|strict-transport'

# Rate limiter: the 11th login in a minute should be 429 with Retry-After
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "$i:%{http_code} " -X POST \
    https://api-trainings.aztu.edu.az/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"nobody@example.com","password":"wrong"}'
done; echo

# The port that must not be reachable from outside
curl --max-time 5 http://api-trainings.aztu.edu.az:8080/actuator/health   # expect a timeout
```

Then in a browser, as the admin you just created:

- sign in, wait past the 15-minute access-token expiry, and confirm you are
  **still** signed in — this is the cookie-refresh path, and the thing TLS
  misconfiguration breaks
- create a course, upload a cover image, upload a lesson video over 100 MB
- confirm an `.svg` and a `.js` are refused by the file picker
- search your own courses from the header and from the course list
- as a student on the public site, register, enrol in a free course, open a lesson

## 10. Redeploy

```bash
cd /opt/trainings/<repo>
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

Flyway applies any new migrations on boot. The API shuts down gracefully (25s), so
in-flight requests finish.

Restart is enough only for the API and for the public site's server-only values
(`INTERNAL_API_URL`, `REVALIDATE_SECRET`). Anything `NEXT_PUBLIC_*` or `VITE_*`
requires `--build`.

## 11. Rollback

```bash
cd /opt/trainings/<repo>
git log --oneline -5
git checkout <previous-sha>
docker compose -f docker-compose.prod.yml up -d --build
```

**Flyway does not roll back.** A release containing a migration is forward-only:
checking the code out at an older commit leaves the newer schema in place. If a
migration is the problem, restore the database from the dump taken before the
deploy (DB_SETUP.md §9) and redeploy the old code against it. Take that dump
*before* any release that adds a migration.

## Pre-launch checklist

- [ ] `DATABASE_PASSWORD` in `api/.env` matches the real Postgres role
- [ ] `JWT_ACCESS_SECRET` is the generated value, not a placeholder
- [ ] `/opt/uploads` exists and is owned by `1001:1001`
- [ ] UFW denies 8080, 3000 and 8081; allows 22, 80, 443
- [ ] TLS live on all three hostnames, HTTP redirects to HTTPS
- [ ] Host nginx sets `X-Real-IP`, `X-Forwarded-For` and `X-Forwarded-Proto`
- [ ] `client_max_body_size 550m` on the API vhost
- [ ] SMTP filled in and `MAIL_ENABLED=true` (otherwise password reset and email
      verification silently do nothing for real users)
- [ ] First admin created, `ADMIN_SELF_REGISTER=false` again
- [ ] `SWAGGER_ENABLED=false`, `/actuator/prometheus` not publicly reachable
- [ ] Nightly `pg_dump` **and** `/opt/uploads` backup scheduled, restore drilled once
- [ ] `PAYMENTS_ENABLED=false` while no payment provider is integrated
