# Deploying `eduplatform-backend`

Spring Boot 3.3 / Java 17 / Postgres + Flyway. The image is self-contained and
defaults to the **prod** profile.

> **Deploying the whole platform for the first time?** Start with
> [docs/operations/deployment.md](docs/operations/deployment.md) — the cross-repo
> runbook covering this service, the public site and the admin portal in order,
> plus the host firewall, TLS and the first-admin bootstrap. This file is the
> reference for *this* service's configuration; that one is the sequence.

```bash
docker build -t eduplatform-backend:latest .
docker run -p 8080:8080 --env-file .env -v /opt/uploads:/opt/uploads eduplatform-backend:latest
```

In production the stack is started from this directory instead, which wires the
volume and host networking for you:

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

## Required environment

Startup **fails loudly** if any of these is missing — that is deliberate, so a
misconfigured deploy never silently connects to the wrong database or signs
tokens with a known key.

| Variable | Notes |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` |
| `DATABASE_USERNAME` | |
| `DATABASE_PASSWORD` | Set it to an empty value deliberately if the database uses IAM or certificate auth — startup then warns instead of failing. |
| `JWT_ACCESS_SECRET` | ≥ 32 bytes. `openssl rand -base64 48`. Rejected if it contains `change-me`, `dev-only` or `insecure`. |

Everything else has a safe default — see [.env.example](.env.example). The ones
you almost certainly want to set:

| Variable | Default | Notes |
| --- | --- | --- |
| `APP_BASE_URL` | `http://localhost:8080` | Used to build OAuth redirect URIs. |
| `CORS_ALLOWED_ORIGINS` | localhost list | Set to the real frontend + portal origins. Startup warns if it still contains `localhost`. |
| `FRONTEND_PUBLIC_URL` / `FRONTEND_PORTAL_URL` | localhost | Used in emails and redirects. |
| `STORAGE_LOCAL_DIR` | `/opt/uploads` | Must equal the container side of the mounted volume — see [Uploads](#uploads). |
| `PAYMENTS_ENABLED` | `false` | Free-only mode. See [Free-only mode](#free-only-mode). |
| `RATE_LIMIT_ENABLED` | `true` | Per-IP buckets on the unauthenticated auth endpoints. Leave on. |
| `UPLOAD_MAX_IMAGE_MB` / `UPLOAD_MAX_VIDEO_MB` / `UPLOAD_MAX_DOCUMENT_MB` | `10` / `512` / `25` | See [Upload sizes](#upload-sizes). |
| `MAIL_ENABLED` | `false` | While false, OTP/verification/password-reset emails are logged, not sent — so those flows do not work for real users. |
| `MAIL_FROM` | `no-reply@eduplatform.local` | Envelope sender; set it to a real mailbox on your domain. |
| `SWAGGER_ENABLED` | `false` | Swagger UI and `/v3/api-docs` are off in prod. |
| `TRUST_FORWARD_HEADERS` | `true` in prod | Only correct when nothing but your own proxy can reach the app's port — see [TLS / reverse proxy](#tls--reverse-proxy). |
| `COOKIE_SECURE` | `auto` | `Secure` flag on the portal's refresh-token cookie. `auto` = on everywhere but the `dev` profile. |
| `ADMIN_SELF_REGISTER` | `false` | Bootstrap only; refused automatically once any `ADMIN`/`SUPER_ADMIN` exists. |

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

Three ceilings have to agree, and the smallest one wins:

| Where | Setting | Value |
| --- | --- | --- |
| API, per media kind | `UPLOAD_MAX_IMAGE_MB` / `UPLOAD_MAX_VIDEO_MB` / `UPLOAD_MAX_DOCUMENT_MB` | 10 / 512 / 25 MB |
| API, multipart request | `spring.servlet.multipart.max-file-size` / `max-request-size` | 512 MB |
| Admin portal nginx | `client_max_body_size` | 550m |

Keep nginx above the API limits. If it is the smaller one, the uploader gets a
bare `413` from the proxy with no typed error body, so the portal cannot tell the
user what actually went wrong. The streaming video `PUT` is *not* governed by the
multipart settings — its body is read as a raw stream and capped in
`VideoService` from `app.uploads.max-video-mb`.

## Free-only mode

No payment provider is integrated. With `PAYMENTS_ENABLED=false` (the default):

- the public catalogue serves **free courses only**, so nobody can reach a
  checkout that cannot charge them;
- order creation is refused with a typed error;
- the public site offers no pay/buy path.

The payment model, orders and prices all still exist — this is one flag, not a
deletion. Before flipping it to `true`, wire an actual provider and re-check the
public catalogue and `OrderService`.

## Rate limiting

`RATE_LIMIT_ENABLED=true` puts per-IP token buckets in front of the
unauthenticated auth endpoints (login, register, OTP start/verify, password
forgot/reset); a caller over the limit gets `429 Too Many Requests`.

The limiter is only as good as the client IP it keys on, so `TRUST_FORWARD_HEADERS`
has to be correct for your topology (see below). Set wrongly to `true` on a port
that is reachable directly, it lets a caller rotate `X-Forwarded-For` and take a
fresh bucket per request.

Only turn it off for a load test against a non-public deployment.

## What the `prod` profile changes

`SPRING_PROFILES_ACTIVE=prod` is baked into the image
(`application-prod.properties`):

- **Flyway runs `classpath:db/migration` only.** The `dev` profile also applies
  `classpath:db/dev`, which creates login-ready `ADMIN` and `SUPER_ADMIN`
  accounts sharing the password `Password123!`. Those must never reach a
  production database — `StartupSecurityValidator` refuses to boot if `db/dev`
  is on the Flyway path outside `dev`.
- Error responses drop messages, binding errors, and stack traces.
- Swagger/OpenAPI off, actuator limited to `health,info`.
- `logging.level.com.eduplatform` drops from `DEBUG` to `INFO`.
- Graceful shutdown (25s) so rolling deploys drain in-flight requests.

## Health

`GET /actuator/health` — suitable for a load balancer.
`management.endpoint.health.probes.enabled=true` also exposes
`/actuator/health/liveness` and `/actuator/health/readiness`; readiness is scoped
to the database, so an SMTP outage cannot eject a serving instance. The container
`HEALTHCHECK` and the compose healthchecks use readiness for that reason.

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
those is the same as publishing it.

## TLS / reverse proxy

**No TLS configuration ships in this repo.** The app listens on plain HTTP on
`SERVER_PORT` (8080), and `docker-compose.prod.yml` uses `network_mode: host`, so
it binds the host's port directly — there is no published Docker port and no
certificate anywhere in the image. Certificates, HSTS and the `http → https`
redirect are the reverse proxy's job (nginx / Caddy on the host, or the
university's ingress) and live outside this codebase.

Two consequences worth being explicit about:

- **`TRUST_FORWARD_HEADERS=true` is only correct while nothing but your own proxy
  can reach port 8080.** The app then takes the client IP from `X-Forwarded-For` /
  `X-Real-IP`. If that port is reachable directly, any caller can set those
  headers and walk past the IP blocklist, the rate limiter and audit attribution.
  Close it at the firewall (`ufw deny 8080` works here precisely *because* host
  networking is used — a published Docker port would bypass UFW), or set
  `TRUST_FORWARD_HEADERS=false`.
- The proxy must pass `X-Forwarded-Proto: https`, or absolute URLs the app builds
  (OAuth redirects, email links) come out as `http://`. `APP_BASE_URL` should be
  the public `https://` origin regardless.

## First deploy checklist

1. Provision an empty Postgres database. Flyway creates the schema on first boot
   (`baseline-on-migrate=true`).
2. Generate `JWT_ACCESS_SECRET` with `openssl rand -base64 48`.
3. Create and chown the upload directory:
   `sudo mkdir -p /opt/uploads && sudo chown -R 1001:1001 /opt/uploads`.
   `STORAGE_PROVIDER` stays `LOCAL` — there is no S3 implementation to switch to.
4. Set `CORS_ALLOWED_ORIGINS` to the real frontend origins and `APP_BASE_URL` to
   the public `https://` origin.
5. Fill in SMTP (`MAIL_*`) and set `MAIL_ENABLED=true`. Until you do, OTP,
   e-mail verification and password reset are only logged — which means admin
   bootstrap and tutor sign-up cannot complete for a real user.
6. Set `ADMIN_SELF_REGISTER=true`, register the first admin, then set it back to
   `false` and redeploy. (Admin self-registration is bootstrap-only: it is
   refused automatically once any `ADMIN`/`SUPER_ADMIN` exists.)
7. Confirm TLS terminates in front of the app and that port 8080 is not reachable
   from outside the host — `TRUST_FORWARD_HEADERS=true` depends on it.
8. Leave `PAYMENTS_ENABLED=false` until a payment provider is actually
   integrated.

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
