# Deploying `eduplatform-backend`

Spring Boot 3.3 / Java 17 / Postgres + Flyway. The image is self-contained and
defaults to the **prod** profile.

> **Deploying the whole platform for the first time?** Start with
> [docs/operations/deployment.md](docs/operations/deployment.md) — the cross-repo
> runbook covering this service, the public site and the admin portal in order,
> plus the host firewall, TLS and the first-admin bootstrap. This file is the
> reference for *this* service's configuration; that one is the sequence.

In production the stack is started from this directory, which wires the volume,
host networking, the profile, the stop timeout and the memory limit for you:

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

Without compose, the equivalent is:

```bash
docker build -t eduplatform-backend:latest .
docker run -d --name eduplatform-backend --network host --env-file .env \
  -e SPRING_PROFILES_ACTIVE=prod --stop-timeout 30 --memory 2g \
  -v /opt/uploads:/opt/uploads eduplatform-backend:latest
```

`-e SPRING_PROFILES_ACTIVE=prod` is not optional. Every value in `--env-file`
beats the image's `ENV SPRING_PROFILES_ACTIVE=prod`, and `-e` beats `--env-file`.
A `.env` copied from an older `.env.example` says `dev`, and under `dev` the API
seeds `superadmin@eduplatform.local` / `Password123!`, turns Swagger on, and skips
every startup check, including the one that refuses the public placeholder JWT
secret. Anyone could then sign their own `SUPER_ADMIN` token. `--network host`
rather than `-p 8080:8080`: a published port bypasses UFW (see
[TLS / reverse proxy](#tls--reverse-proxy)).

## Required environment

Startup **fails loudly** if any of these is missing — that is deliberate, so a
misconfigured deploy never silently connects to the wrong database or signs
tokens with a known key.

| Variable | Notes |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod`. The image defaults to it and `docker-compose.prod.yml` pins it; `.env` must not set anything else. Under `dev` alone none of the checks below run (`dev` next to another profile is refused). |
| `DATABASE_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` |
| `DATABASE_USERNAME` | |
| `DATABASE_PASSWORD` | Set it to an empty value deliberately if the database uses IAM or certificate auth — startup then warns instead of failing. |
| `JWT_ACCESS_SECRET` | ≥ 32 bytes. `openssl rand -base64 48`. Rejected if it contains `change-me`, `dev-only` or `insecure`. |

Everything else has a safe default — see [.env.example](.env.example). The ones
you almost certainly want to set:

| Variable | Default | Notes |
| --- | --- | --- |
| `APP_BASE_URL` | `http://localhost:8080` | Used to build OAuth redirect URIs. |
| `CORS_ALLOWED_ORIGINS` | localhost list | Exactly `https://trainings.aztu.edu.az,https://dashboard-trainings.aztu.edu.az`. Never `*` and never a wildcard pattern such as `https://*.aztu.edu.az`: CORS here allows credentials, so every origin that matches can call `POST /api/auth/refresh` with the portal's refresh cookie and read a fresh admin token. The dashboard origin stays although the portal calls REST same-origin, because the `/ws` handshake is checked against this list too. Outside `dev`, startup refuses a wildcard, `null` or plain-http origin, and warns about `localhost`. |
| `FRONTEND_PUBLIC_URL` | `http://localhost:3000` | The public site's origin. Password-reset and e-mail-verification links, and the redirect after OAuth sign-in, are built from it. (`FRONTEND_PORTAL_URL` is not read by anything; a leftover line is harmless.) |
| `STORAGE_LOCAL_DIR` | `/opt/uploads` | Must equal the container side of the mounted volume — see [Uploads](#uploads). |
| `PAYMENTS_ENABLED` | `false` | Free-only mode. See [Free-only mode](#free-only-mode). |
| `RATE_LIMIT_ENABLED` | `true` | Per-IP buckets on the unauthenticated auth endpoints. Leave on. |
| `UPLOAD_MAX_IMAGE_MB` / `UPLOAD_MAX_VIDEO_MB` / `UPLOAD_MAX_DOCUMENT_MB` | `200` / `512` / `200` | See [Upload sizes](#upload-sizes). |
| `MAIL_ENABLED` | `false` | While false, OTP/verification/password-reset emails are logged (body included), not sent — so those flows do not work for real users. See [Mail](#mail). |
| `MAIL_FROM` | `no-reply@eduplatform.local` | Envelope sender; set it to a real mailbox on your domain. |
| `SWAGGER_ENABLED` | `false` | Swagger UI and `/v3/api-docs` are off in prod. |
| `COOKIE_SECURE` | `auto` | `Secure` flag on the portal's refresh-token cookie. `auto` = on everywhere but the `dev` profile. |
| `ADMIN_SELF_REGISTER` | `false` | Bootstrap only. Admin self-registration is accepted only while this is `true` **and** no `ADMIN`/`SUPER_ADMIN` exists; otherwise `403 ADMIN_REGISTER_CLOSED`. |

`TRUST_FORWARD_HEADERS` no longer exists — see [Client IP](#client-ip). A
leftover line in `.env` is ignored and does not affect startup.

## Uploads

`STORAGE_PROVIDER=LOCAL` is **the only working mode**. `LocalStorageService` is
the sole `StorageService` bean in the codebase and it is not conditional on the
property, so setting `STORAGE_PROVIDER=S3` is accepted, changes nothing, and
still writes to local disk. Object storage needs an implementation first; the
`S3_*` keys are placeholders for it.

That makes the mounted volume the media store, and one path has to match in four
places — the image default (`ENV STORAGE_LOCAL_DIR`), the image `VOLUME`, the
compose mount, and `STORAGE_LOCAL_DIR` in `.env`. All four are **`/opt/uploads`**.
If they disagree, uploads are written to the container's own layer, every request
succeeds, and the files are destroyed by the next redeploy with nothing in the
logs to say so.

The container runs as the pinned non-root user `1001:1001`, so the host directory
must be owned by it or every write fails with `EACCES`:

```bash
sudo mkdir -p /opt/uploads
sudo chown -R 1001:1001 /opt/uploads
```

Back this directory up. It is not reproducible from the database — the DB stores
only object keys.

### Upload sizes

Uploads take one of two paths, and on each the smallest ceiling wins:

| Where | Setting | Value |
| --- | --- | --- |
| API, per media kind | `UPLOAD_MAX_IMAGE_MB` / `UPLOAD_MAX_VIDEO_MB` / `UPLOAD_MAX_DOCUMENT_MB` | 200 / 512 / 200 MB |
| API, multipart (`POST /api/media`: images and documents) | `spring.servlet.multipart.max-file-size` / `max-request-size` | 210 MB / 220 MB |
| Host nginx, **both** the `dashboard-trainings` and the `api-trainings` vhost, and the admin portal's own nginx | `client_max_body_size` | 550m |

**Images and documents** go multipart to `POST /api/media`, so they pass the
per-kind ceiling, the 210 MB / 220 MB multipart limits and nginx. The multipart
limits are sized for the largest multipart upload (a 200 MB image or document),
not for video, on purpose: Tomcat spools a multipart part to disk in full before
any controller sees it, so that figure is how much disk a caller can make the
server write per request. For the same reason `POST /api/media` is open only to
`TUTOR`, `ADMIN` and `SUPER_ADMIN` accounts, and the check is made in the
security filter chain, which answers `403` before any of the body is read — a
`@PreAuthorize` on the controller would run only after the spooling. Nothing a
participant does uploads a file (the public site never does), so a
self-registered account cannot use this endpoint to fill the disk. Raise the
multipart limits together with `UPLOAD_MAX_IMAGE_MB` / `UPLOAD_MAX_DOCUMENT_MB`
if either ever goes past 210 — otherwise the servlet rejects the file before the
typed per-kind error can be produced.

**Video** bypasses multipart entirely: the portal streams it with
`PUT /api/videos/{id}/content`, whose body is read as a raw stream and capped
while the bytes arrive in `VideoService` from `app.uploads.max-video-mb`. Only
that ceiling and nginx apply.

Keep every nginx on the way above the API limits. If one is the smaller, the
uploader gets a bare `413` from the proxy with no typed error body, so the portal
cannot tell the user what actually went wrong. Which nginx instances an upload
passes depends on who sends it:

- the admin portal, built with `VITE_API_BASE_URL=/api` (production): host
  `dashboard-trainings` vhost, then the portal container's nginx, then the API;
- the public site, and the portal if built with an absolute `VITE_API_BASE_URL`:
  host `api-trainings` vhost, then the API.

Both host vhosts therefore need `550m`, and with nginx's 1 MB default the first
path failed for every cover over 1 MB, every document and every video. Both are
in [deploy/nginx/trainings.conf](deploy/nginx/trainings.conf).

## Free-only mode

No payment provider is integrated. With `PAYMENTS_ENABLED=false` (the default):

- the public catalogue lists **free courses only**, so nobody can reach a
  checkout that cannot charge them. An explicit `free=false` returns an empty
  page rather than being quietly turned into a free-course listing;
- a published paid course is still served by slug
  (`GET /api/public/courses/{slug}`) — the public site shows it as unavailable;
- free enrolment is refused for any course that is not free
  (`422 PAYMENT_REQUIRED`), so knowing a paid course's id does not get anyone in;
- order creation is refused with a typed error;
- the public site offers no pay/buy path.

The payment model, orders and prices all still exist — this is one flag, not a
deletion. Before flipping it to `true`, wire an actual provider and re-check the
public catalogue and `OrderService`.

## Rate limiting

`RATE_LIMIT_ENABLED=true` puts per-IP token buckets in front of the
unauthenticated auth endpoints (login, register, OTP start/verify, password
forgot/reset); a caller over the limit gets `429 Too Many Requests`.

The limiter is only as good as the client IP it keys on. That is the address
Tomcat's `RemoteIpValve` resolves from `X-Forwarded-For` — see
[Client IP](#client-ip) for what it trusts, and why port 8080 must stay closed.

Only turn it off for a load test against a non-public deployment.

## Mail

- **`MAIL_ENABLED=false`**: nothing is sent. Each message is logged at INFO with
  the `[mail:disabled]` prefix, **body included**. That is deliberate — it is how
  the operator reads the admin-bootstrap OTP
  ([deployment.md §8](docs/operations/deployment.md#8-create-the-first-admin)) —
  but the body also carries live password-reset links and OTPs for anyone who
  asks for one, so treat the container logs as secret while mail is off. It is
  not a production setting: expert sign-up, e-mail verification and password
  reset never reach the user. Turn mail on as soon as the first admin exists.
- **`MAIL_ENABLED=true`**: a failed send is logged with the recipient and subject
  only, never the body. The failure does not fail the request, so a broken SMTP
  setup shows up in the log, not as an error the user sees. After turning it on,
  request one real password reset and confirm the mail arrives; that is the only
  proof. `MAIL_USERNAME`/`MAIL_PASSWORD` are the relay's credentials (the AzTU
  relay, or a Gmail app password for the `MAIL_FROM` mailbox, not its login
  password).
- **SMTP timeouts**: connect 5 s, read 10 s, write 10 s
  (`spring.mail.properties.mail.smtp.connectiontimeout` / `timeout` /
  `writetimeout`). JavaMail's own default is to wait forever, and some sends
  happen inside a database transaction, so without them a black-holed SMTP port
  pins the request and a pool connection until the pool runs dry. Check egress
  before enabling mail:
  `timeout 5 bash -c '</dev/tcp/smtp.gmail.com/587' && echo open || echo BLOCKED`.
- The SMTP **health indicator is off** whatever `MAIL_ENABLED` says — see
  [Health](#health).

## What the `prod` profile changes

`SPRING_PROFILES_ACTIVE=prod` is baked into the image and pinned again under
`environment:` in `docker-compose.prod.yml`, because a value in `.env` would beat
the image's default (`application-prod.properties`):

- **Flyway runs `classpath:db/migration` only.** The `dev` profile also applies
  `classpath:db/dev`, which creates login-ready `ADMIN` and `SUPER_ADMIN`
  accounts sharing the password `Password123!`. Those must never reach a
  production database — `StartupSecurityValidator` refuses to boot if `db/dev`
  is on the Flyway path outside `dev`.
- Error responses drop messages, binding errors, and stack traces.
- Swagger/OpenAPI off, actuator limited to `health,info`.
- `logging.level.com.eduplatform` drops from `DEBUG` to `INFO`.
- Graceful shutdown: on `docker compose stop` or a redeploy, requests already
  running get up to 25s to finish; new ones are refused. There is one instance and
  no rolling deploy, so the API is down from the stop until the new container
  reports ready. `stop_grace_period: 30s` in
  `docker-compose.prod.yml` is what lets the 25s happen: Docker's default is to
  kill the container after 10s.

## Memory

`docker-compose.prod.yml` caps the container at `mem_limit: 2g`, and the JVM takes
75% of that for the heap (`-XX:MaxRAMPercentage=75` in the Dockerfile), about
1.5 GiB. Without the cap the 75% is of the whole host, which it shares with
PostgreSQL and the two frontends. Resident memory is about 730 MiB under light
load, so do not go below 2g; raise it if the host has room and the heap fills
(`docker stats eduplatform-backend`).

## Health

Point load balancers and uptime monitors at **`/actuator/health/readiness`**.
It is scoped to the database (`readinessState,db`), so a third-party outage cannot
eject a serving instance; the container `HEALTHCHECK` and the compose healthchecks
use it for that reason. `management.endpoint.health.probes.enabled=true` also
exposes `/actuator/health/liveness`.

The aggregate `/actuator/health` is anonymous; do not probe it. The mail health
indicator is switched off (`management.health.mail.enabled=false`) regardless of
`MAIL_ENABLED`, because with it on every call to the aggregate opens an SMTP
session and logs in with the real mailbox credentials: a stalled SMTP host makes
the endpoint hang, and anyone who can reach it can make the server log in to Gmail
in a loop until the account is throttled and real OTP mail stops. Readiness never
included mail, so nothing that routes traffic loses a signal.

The documented `api-trainings` vhost refuses `/actuator` to anything but
`127.0.0.1` ([deployment.md §6](docs/operations/deployment.md#6-tls-and-the-host-reverse-proxy)),
so monitor from the host (`http://127.0.0.1:8080/actuator/health/readiness`), or
add the monitor's address to that location with another `allow` line.

## Metrics

`/actuator/prometheus` is **not exposed**, in prod or in the container: its output
includes a complete URI inventory plus JVM, connection-pool and datasource
internals — a free map of the application for anyone who can reach the port. What
closes it is the actuator exposure list, not Spring Security, so it stays closed
even though the path itself is reachable without a token.

To scrape it from an internal monitoring host, add it back:

```properties
management.endpoints.web.exposure.include=health,info,prometheus
```

and then make sure it is genuinely unreachable from the internet — the app binds
plain HTTP on the host (see below), so "internal" is not automatic. Either bind
the scrape target to a private interface and `ufw allow from <scraper-ip> to any
port 8080`, or put authentication in front of it. Re-listing it without one of
those is the same as publishing it. The vhost's `/actuator` restriction does not
count as one: it only covers traffic that goes through nginx, and anything that
can reach port 8080 directly never does.

## TLS / reverse proxy

**The app itself does no TLS.** It listens on plain HTTP on
`SERVER_PORT` (8080), and `docker-compose.prod.yml` uses `network_mode: host`, so
it binds the host's port directly — there is no published Docker port and no
certificate anywhere in the image. Certificates, HSTS and the `http → https`
redirect are the host nginx's job. Its config for all three hostnames is
[deploy/nginx/trainings.conf](deploy/nginx/trainings.conf), installed as
`/etc/nginx/sites-available/trainings` by
[deployment.md §6](docs/operations/deployment.md#6-tls-and-the-host-reverse-proxy).
Nothing copies it there on a redeploy, so diff it against the live file after
every `git pull`.

Consequences worth being explicit about:

- **Keep port 8080 closed to everything but the host.** Anything that reaches it
  directly skips TLS and the vhost's `/actuator` restriction, and — see
  [Client IP](#client-ip) — a caller from a private address counts as a trusted
  proxy, so if this host sits on a private network (a campus LAN), any machine on
  it could name whatever client IP it liked and walk past the IP blocklist, the
  rate limiter and audit attribution. `ufw deny 8080` works here precisely
  *because* host networking is used — a published Docker port would bypass UFW.
- The proxy must pass `X-Forwarded-Proto: https`, or absolute URLs the app builds
  (OAuth redirects, email links) come out as `http://`. `APP_BASE_URL` should be
  the public `https://` origin regardless.

### Client IP

`server.forward-headers-strategy=native` hands forwarded headers to Tomcat's
`RemoteIpValve`. The app reads `request.getRemoteAddr()`, which the valve has
already resolved, and the rate limiter, the IP blocklist and audit records all
key on that value.

- The valve honours `X-Forwarded-For` and `X-Forwarded-Proto` only when the TCP
  peer is a trusted internal proxy — Spring Boot's default
  `server.tomcat.remoteip.internal-proxies`: loopback, the RFC 1918 ranges (10/8,
  172.16/12 — which contains Docker's bridge networks — and 192.168/16),
  link-local 169.254/16 and carrier-grade NAT 100.64/10. From any other peer the
  headers are ignored and the socket address is the client.
- It reads `X-Forwarded-For` **right to left**, skips entries that are themselves
  internal proxies, and takes the first one that is not. Behind the host nginx
  that is the `$remote_addr` nginx appended with `$proxy_add_x_forwarded_for`;
  anything a client prepended sits further left and is never reached.
- `X-Real-IP` and the RFC 7239 `Forwarded` header are not read at all.
- The default range is deliberately not narrowed to loopback: the admin portal's
  nginx and the public site's BFF also call the API and pass the real client in
  `X-Forwarded-For`, and a container on a bridged network (the public site under
  its `docker-compose.yml`) connects from a Docker bridge address, not loopback.
  Narrowing the range would make the API ignore that header and put all of those
  users in one rate-limit bucket.

One residual: a client whose own address is private — an on-campus machine that
reaches nginx without NAT — is itself inside the trusted range, so the valve walks
past it into whatever that client prepended. Internet clients cannot do this, and
neither can requests through the admin portal's nginx or the public site's BFF,
which forward a single clean address. If it matters, make the `api-trainings`
vhost send `proxy_set_header X-Forwarded-For $remote_addr;` instead — correct
only while that nginx is the first hop; with a load balancer in front of it,
every client would share the balancer's address.

`TRUST_FORWARD_HEADERS` (`app.security.trust-forward-headers`) is gone. Setting it
to `false` never protected anything: Spring's forwarded-header filter rewrote the
remote address from `X-Forwarded-For` and `Forwarded` regardless. A leftover line
in `.env` is ignored; delete it when convenient.

## First deploy checklist

1. Provision an empty Postgres database. Flyway creates the schema on first boot
   (`baseline-on-migrate=true`).
2. Generate `JWT_ACCESS_SECRET` with `openssl rand -base64 48`.
3. Create and chown the upload directory:
   `sudo mkdir -p /opt/uploads && sudo chown -R 1001:1001 /opt/uploads`.
   `STORAGE_PROVIDER` stays `LOCAL` — there is no S3 implementation to switch to.
4. Set `CORS_ALLOWED_ORIGINS` to exactly the two frontend origins (see the table
   above: never `*`) and `APP_BASE_URL` to the public `https://` origin.
5. Set `ADMIN_SELF_REGISTER=true`, register the first admin straight away, then
   set it back to `false` and redeploy. Registration needs the flag **and** zero
   existing `ADMIN`/`SUPER_ADMIN` accounts, so once yours exists the flag alone
   cannot reopen it; until then, whoever reaches the portal first gets the
   account, so keep the window short. The bootstrap account is an `ADMIN` —
   promote it to `SUPER_ADMIN` once in SQL
   ([deployment.md §8](docs/operations/deployment.md#8-create-the-first-admin)).
   With mail still off, the OTP is read from the log ([Mail](#mail)), so do this
   step before the next one.
6. Fill in SMTP (`MAIL_*`), set `MAIL_ENABLED=true`, recreate the container
   (`up -d`), and run one real password reset end to end. Until then OTP, e-mail
   verification and password reset are only logged, so expert sign-up and
   password reset cannot complete for a real user.
7. Confirm TLS terminates in front of the app and that port 8080 is not reachable
   from outside the host — see [TLS / reverse proxy](#tls--reverse-proxy) for why
   that is a security control and not tidiness.
8. Leave `PAYMENTS_ENABLED=false` until a payment provider is actually
   integrated.
9. Install the backups and take the first one
   ([DB_SETUP.md §9](DB_SETUP.md#9-backups)).

## Local development

`SPRING_PROFILES_ACTIVE=dev` (the default outside Docker) falls back to
`localhost:5432/eduplatform`, a dev-only signing key and `./var/uploads` for
media, so `./mvnw spring-boot:run` works against `docker compose up postgres`
with no setup. To point local runs elsewhere, create a gitignored `.env` in the
repo root — `spring.config.import` picks it up automatically.

The full local stack (Postgres + backend) is `docker compose up --build`; that
compose file intentionally runs the `dev` profile.

## CI

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs `./mvnw verify` on
push/PR to `main` and `develop` against a Postgres service container (the
migrations use Postgres-only types, so an in-memory database cannot stand in),
and builds the Docker image on push. The JWT secret is generated per run rather
than committed.
