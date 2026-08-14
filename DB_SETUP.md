# Creating the production database

Self-hosted PostgreSQL 16 on the same server as the backend. Written for Ubuntu
22.04 / 24.04 with sudo access; the SQL steps are the same anywhere.

You do **not** create any tables by hand. Flyway builds the entire schema on the
backend's first boot. All you are doing here is producing an empty database, a
role that owns it, and three extensions.

Why 16 and not 17: Flyway 10.10 (pinned in `pom.xml`) supports PostgreSQL up to
16. On 17 it logs `Flyway upgrade recommended: PostgreSQL 17.10 is newer than
this version of Flyway and support has not been tested`.

---

## 1. Install PostgreSQL 16

Ubuntu's own repo ships 14 (22.04) or 16 (24.04). Use the PostgreSQL project's
repo so the version is explicit either way:

```bash
sudo apt update
sudo apt install -y curl ca-certificates
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail \
  https://www.postgresql.org/media/keys/ACCC4CF8.asc
sudo sh -c 'echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
> /etc/apt/sources.list.d/pgdg.list'
sudo apt update
sudo apt install -y postgresql-16
```

> **Run these as separate commands, not chained with `&&`.** On a server
> installed from ISO, apt often still lists the install media
> (`file:/cdrom`). Every real repository updates fine, but the missing
> `/cdrom/dists/<release>/Release` makes `apt update` exit non-zero — which
> silently skips a chained `apt install`, leaving you with no
> `postgresql.service` and no `pg_isready`. To remove the noise for good:
>
> ```bash
> grep -rn "cdrom" /etc/apt/sources.list /etc/apt/sources.list.d/ 2>/dev/null
> sudo sed -i '/cdrom/s/^[[:space:]]*deb/#deb/' /etc/apt/sources.list
> ```

Confirm it is running and set to start on boot:

```bash
sudo systemctl enable --now postgresql
pg_isready                      # expect: /var/run/postgresql:5432 - accepting connections
psql --version                  # expect: psql (PostgreSQL) 16.x
```

## 2. Create the role and the database

`createuser --pwprompt` prompts for the password instead of taking it as an
argument, so it never lands in your shell history or the process list.

```bash
sudo -u postgres createuser --pwprompt eduplatform
sudo -u postgres createdb --owner=eduplatform --encoding=UTF8 eduplatform
```

Generate the password first with `openssl rand -hex 24` and store it in your
secret manager — you need it again in step 6.

Hex rather than base64 on purpose: base64 output contains `+`, `/` and `=`,
which are the characters that later need escaping in `.env` files, connection
URLs and shell quoting. At 24 bytes hex is equally strong and never needs
quoting.

If authentication fails afterwards, note that PostgreSQL reports `password
authentication failed` for a role that does not exist as well as for a wrong
password. Check the role actually landed in the cluster you are connecting to —
`pg_lsclusters` to list clusters and ports, then `psql -p <port> -c '\du'`.
`createuser` uses the default cluster, which is not always the one on 5432.

**The `--owner` matters.** Since PostgreSQL 15 the `public` schema no longer
grants `CREATE` to everyone. A role that merely has `CONNECT` cannot create
tables, and Flyway fails on the first migration. Owning the database confers
that right via `pg_database_owner`.

## 3. Install the required extensions

`V1__init.sql` needs all three:

| Extension | Used for |
| --- | --- |
| `pgcrypto` | `gen_random_uuid()`, `digest()` — every primary key |
| `citext` | case-insensitive email column |
| `btree_gist` | room-booking exclusion constraint |

```bash
sudo -u postgres psql -d eduplatform -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto;'
sudo -u postgres psql -d eduplatform -c 'CREATE EXTENSION IF NOT EXISTS citext;'
sudo -u postgres psql -d eduplatform -c 'CREATE EXTENSION IF NOT EXISTS btree_gist;'
```

Flyway also issues these as `CREATE EXTENSION IF NOT EXISTS`, so this step is
technically optional — all three are *trusted* extensions in PG 13+, installable
by a database owner. Doing it as `postgres` up front means the app role never
needs elevated rights, and a permissions problem surfaces now rather than
halfway through a migration.

Verify:

```bash
sudo -u postgres psql -d eduplatform -c '\dx'
```

## 4. Prove the app role can actually write

Do this now — it is the failure that otherwise appears as a cryptic Flyway error
on first deploy.

```bash
psql "postgresql://eduplatform@127.0.0.1:5432/eduplatform" \
  -c 'CREATE TABLE _perm_check(id int); DROP TABLE _perm_check;'
```

Expect `CREATE TABLE` / `DROP TABLE`. If it reports `permission denied for
schema public`, step 2's ownership did not apply:

```bash
sudo -u postgres psql -d eduplatform -c 'GRANT CREATE ON SCHEMA public TO eduplatform;'
```

## 5. Keep it on loopback

The Debian/Ubuntu default is already localhost-only. Confirm rather than assume:

```bash
sudo -u postgres psql -tAc 'SHOW listen_addresses;'   # expect: localhost
sudo ss -lntp | grep 5432                             # expect 127.0.0.1:5432 and/or [::1]:5432 only
```

If `ss` shows `0.0.0.0:5432`, edit `/etc/postgresql/16/main/postgresql.conf` to
`listen_addresses = 'localhost'` and `sudo systemctl restart postgresql`.

Check password auth is required for TCP connections:

```bash
sudo grep -vE '^\s*#|^\s*$' /etc/postgresql/16/main/pg_hba.conf
```

The `127.0.0.1/32` line should say `scram-sha-256`, not `trust`.

Belt and braces at the firewall:

```bash
sudo ufw status                # 5432 must NOT appear as ALLOW from anywhere
```

## 6. Point the backend at it

In the backend's environment (systemd unit, `--env-file`, or your secret store):

```bash
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/eduplatform
DATABASE_USERNAME=eduplatform
DATABASE_PASSWORD=<the password from step 2>
JWT_ACCESS_SECRET=<openssl rand -base64 48>
```

No `sslmode=require` here — the template in `.env.example` carries it for
managed providers, but over loopback it buys nothing. Leave it off.

### If the backend runs in Docker

`127.0.0.1` inside a container is the *container*, not the host, so the above
will fail with "connection refused". On Linux the cleanest fix is to share the
host network, which keeps Postgres bound to loopback:

```bash
docker run --network host --env-file .env eduplatform-backend:latest
```

The alternative — publishing Postgres to the Docker bridge and using
`host.docker.internal` — means listening beyond loopback and widening
`pg_hba.conf` to the container subnet. Prefer `--network host` unless you have a
reason not to.

## 7. First boot

Start the backend and watch the migration run:

```
Migrating schema "public" to version "1 - init"
...
Successfully applied 7 migrations to schema "public", now at version v9
```

**7 migrations, not 9.** Nine means the `dev` profile leaked in and seeded
`ADMIN`/`SUPER_ADMIN` accounts with the shared password `Password123!`. The
`prod` profile refuses to start in that case, so if you see 9, check
`SPRING_PROFILES_ACTIVE`.

Verify from the database side:

```bash
psql "postgresql://eduplatform@127.0.0.1:5432/eduplatform" \
  -c 'select version, description from flyway_schema_history order by installed_rank;' \
  -c "select count(*) from users where email like '%@eduplatform.local';"
```

Expect versions 1,2,3,4,5,8,9 and a seed-account count of **0**.

Then check the app:

```bash
curl -s localhost:8080/actuator/health/readiness    # {"status":"UP"}
```

## 8. Create the first admin

Admin self-registration is bootstrap-only — it is refused automatically once any
`ADMIN`/`SUPER_ADMIN` exists, but leave nothing to chance:

1. Set `ADMIN_SELF_REGISTER=true`, restart.
2. Register your admin account through the portal.
3. Set `ADMIN_SELF_REGISTER=false`, restart.

## 9. Backups

Nothing above protects you from a bad migration or a dropped table.

```bash
sudo -u postgres mkdir -p /var/backups/postgres
sudo -u postgres tee /usr/local/bin/backup-eduplatform.sh >/dev/null <<'EOF'
#!/bin/sh
set -eu
DEST=/var/backups/postgres
pg_dump -Fc eduplatform > "$DEST/eduplatform-$(date +%F-%H%M).dump"
find "$DEST" -name 'eduplatform-*.dump' -mtime +14 -delete
EOF
sudo chmod +x /usr/local/bin/backup-eduplatform.sh
```

Nightly at 03:00, as the `postgres` user:

```bash
sudo crontab -u postgres -e
# 0 3 * * * /usr/local/bin/backup-eduplatform.sh
```

Restore drill (do this once, on a scratch database — an untested backup is not a
backup):

```bash
sudo -u postgres createdb eduplatform_restore_test
sudo -u postgres pg_restore -d eduplatform_restore_test /var/backups/postgres/<file>.dump
sudo -u postgres dropdb eduplatform_restore_test
```

Copy the dumps off this server. A backup on the same disk as the database only
survives the failures that were never going to hurt you.

## 10. Sizing

`DB_POOL_MAX` defaults to 20 connections per backend instance; PostgreSQL's
default `max_connections` is 100. One or two instances is fine. Beyond that,
raise `max_connections` in `postgresql.conf` or lower `DB_POOL_MAX` — each
connection costs memory, so pooling smaller usually beats connecting more.

On a dedicated database server, `shared_buffers` at ~25% of RAM is the standard
starting point; the packaged default (128MB) is deliberately tiny.
