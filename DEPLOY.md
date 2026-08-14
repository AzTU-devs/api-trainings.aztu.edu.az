# Deploying `eduplatform-backend`

Spring Boot 3.3 / Java 17 / Postgres + Flyway. The image is self-contained and
defaults to the **prod** profile.

```bash
docker build -t eduplatform-backend:latest .
docker run -p 8080:8080 --env-file .env eduplatform-backend:latest
```

## Required environment

Startup **fails loudly** if any of these is missing — that is deliberate, so a
misconfigured deploy never silently connects to the wrong database or signs
tokens with a known key.

| Variable | Notes |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` |
| `DATABASE_USERNAME` | |
| `DATABASE_PASSWORD` | |
| `JWT_ACCESS_SECRET` | ≥ 32 bytes. `openssl rand -base64 48`. Rejected if it contains `change-me`. |

Everything else has a safe default — see [.env.example](.env.example). The ones
you almost certainly want to set:

| Variable | Default | Notes |
| --- | --- | --- |
| `APP_BASE_URL` | `http://localhost:8080` | Used to build OAuth redirect URIs. |
| `CORS_ALLOWED_ORIGINS` | localhost list | Set to the real frontend + portal origins. Startup warns if it still contains `localhost`. |
| `FRONTEND_PUBLIC_URL` / `FRONTEND_PORTAL_URL` | localhost | Used in emails and redirects. |
| `STORAGE_PROVIDER` | `LOCAL` | `LOCAL` writes to `/var/uploads` — **mount a volume or use `S3`**, container-local files vanish on redeploy. |
| `MAIL_ENABLED` | `false` | While false, OTP/verification emails are logged, not sent. |
| `SWAGGER_ENABLED` | `false` | Swagger UI and `/v3/api-docs` are off in prod. |
| `TRUST_FORWARD_HEADERS` | `true` in prod | Only correct behind a proxy you control; set `false` if the app is directly internet-facing, otherwise clients can spoof their source IP past the blocklist. |

## What the `prod` profile changes

`SPRING_PROFILES_ACTIVE=prod` is baked into the image
(`application-prod.properties`):

- **Flyway runs `classpath:db/migration` only.** The `dev` profile also applies
  `classpath:db/dev`, which creates login-ready `ADMIN` and `SUPER_ADMIN`
  accounts sharing the password `Password123!`. Those must never reach a
  production database — `StartupSecurityValidator` refuses to boot if `db/dev`
  is on the Flyway path outside `dev`.
- Error responses drop messages, binding errors, and stack traces.
- Swagger/OpenAPI off, actuator limited to `health,info,prometheus`.
- `logging.level.com.eduplatform` drops from `DEBUG` to `INFO`.
- Graceful shutdown (25s) so rolling deploys drain in-flight requests.

## Health

`GET /actuator/health` — used by the Dockerfile `HEALTHCHECK` and suitable for a
load balancer. `management.endpoint.health.probes.enabled=true` also exposes
`/actuator/health/liveness` and `/actuator/health/readiness` for Kubernetes.

## First deploy checklist

1. Provision an empty Postgres database. Flyway creates the schema on first boot
   (`baseline-on-migrate=true`).
2. Generate `JWT_ACCESS_SECRET` with `openssl rand -base64 48`.
3. Set `ADMIN_SELF_REGISTER=true`, register the first admin, then set it back to
   `false` and redeploy. (Admin self-registration is bootstrap-only: it is
   refused automatically once any `ADMIN`/`SUPER_ADMIN` exists.)
4. Set `CORS_ALLOWED_ORIGINS` to the real frontend origins.
5. Point `STORAGE_PROVIDER=S3` (or mount a persistent volume at `/var/uploads`).

## Local development

`SPRING_PROFILES_ACTIVE=dev` (the default outside Docker) falls back to
`localhost:5432/eduplatform` and a dev-only signing key, so `./mvnw
spring-boot:run` works against `docker compose up postgres` with no setup. To
point local runs elsewhere, create a gitignored `.env` in the repo root —
`spring.config.import` picks it up automatically.

The full local stack (Postgres + backend) is `docker compose up --build`; that
compose file intentionally runs the `dev` profile.
