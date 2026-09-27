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
docker run --network host --env-file .env -e SPRING_PROFILES_ACTIVE=prod \
  -v /opt/uploads:/opt/uploads eduplatform-backend:latest
```

`-e SPRING_PROFILES_ACTIVE=prod` is there because anything in `--env-file` beats
the image's own `prod` default, and a `.env` that says `dev` would start this
database's first boot with the seed accounts. In production use
`docker compose -f docker-compose.prod.yml up -d` from the API repo instead: it
does the same, and pins the profile the same way.

The alternative — publishing Postgres to the Docker bridge and using
`host.docker.internal` — means listening beyond loopback and widening
`pg_hba.conf` to the container subnet. Prefer `--network host` unless you have a
reason not to.

## 7. First boot

Start the backend and watch the migration run:

```
Migrating schema "public" to version "1 - init"
...
Successfully applied <N> migrations to schema "public", now at version v<highest>
```

**One migration per file in `src/main/resources/db/migration`**
(`ls src/main/resources/db/migration`), from V1 up to the highest version there,
with 6 and 7 missing. Versions 6 and 7 live in `db/dev` and must never appear. If
they do, the `dev` profile was active and seeded `ADMIN` and `SUPER_ADMIN`
accounts with the shared password `Password123!`.

`StartupSecurityValidator` refuses `dev` combined with any other profile (such as
`prod,dev`) and refuses `db/dev` on the Flyway path outside `dev`, but it stands
down when `dev` is the only profile: that is the local setup, and nothing at
startup can tell it apart from a server set up wrongly. What prevents it on the
server is the profile itself: `docker-compose.prod.yml` pins
`SPRING_PROFILES_ACTIVE=prod`, so `.env` cannot change it. If you see version 6
or 7, stop the backend, check `SPRING_PROFILES_ACTIVE` everywhere it can be set,
and drop and recreate this database before going further. Starting it under
`prod` afterwards fails anyway: Flyway refuses a history that lists migrations
the build does not have.

Verify from the database side:

```bash
psql "postgresql://eduplatform@127.0.0.1:5432/eduplatform" \
  -c 'select version, description from flyway_schema_history order by installed_rank;' \
  -c "select count(*) from users where email like '%@eduplatform.local';"
```

Expect every version in `db/migration` (1 to 5, then 8 up to the highest), never
6 or 7, and a seed-account count of **0**.

Then check the app:

```bash
curl -s localhost:8080/actuator/health/readiness    # {"status":"UP"}
```

## 8. Create the first admin

Admin self-registration is bootstrap-only — it is refused automatically once any
`ADMIN`/`SUPER_ADMIN` exists, but leave nothing to chance:

1. Set `ADMIN_SELF_REGISTER=true` in `.env` and apply it with
   `docker compose -f docker-compose.prod.yml up -d`. `restart` would keep the old
   value, because `.env` is read only when the container is created.
2. Register your admin account through the portal.
3. Set `ADMIN_SELF_REGISTER=false` and run the same `up -d`.

The exact commands are in
[deployment.md §8](docs/operations/deployment.md#8-create-the-first-admin).

## 9. Backups

Nothing above protects you from a bad migration, a dropped table or a lost disk.
The backup is three scripts in [deploy/backup/](deploy/backup/):

| Script | Runs on | Does |
| --- | --- | --- |
| `backup-eduplatform.sh` | this server, nightly | Dumps the database, snapshots `/opt/uploads`, prunes what is older than 14 days, records success |
| `pull-eduplatform-backup.sh` | a second machine, nightly | Copies the newest backup off this server |
| `restore-eduplatform.sh` | this server, when needed | Puts the database and the uploads back |

The procedure this replaces made no backups at all. It ran its steps as
`postgres`, which on stock Ubuntu can neither create `/var/backups/postgres` nor
write to `/usr/local/bin`. Both steps failed with `Permission denied`, `chmod`
then failed too, and cron called a missing script every night without a word.
The backup now runs as root. `pg_dump` still runs as `postgres`, but the script
opens the output file, so `postgres` needs no access to the backup directory.

### Install

```bash
cd /opt/trainings/api-trainings.aztu.edu.az
sudo apt install -y rsync

# Backups are root:eduplatform-backup, files 0640 and directories 2750: this group
# can read them (the off-host puller below is its only member) and nobody else can.
sudo groupadd --system eduplatform-backup
sudo install -d -o root -g eduplatform-backup -m 2750 /var/backups/eduplatform

sudo install -o root -g root -m 0755 deploy/backup/backup-eduplatform.sh  /usr/local/sbin/backup-eduplatform
sudo install -o root -g root -m 0755 deploy/backup/restore-eduplatform.sh /usr/local/sbin/restore-eduplatform
```

These are copies, not links into the checkout. Cron runs the backup as root, and
a root job must not run a file that anyone with write access to the repo could
change. Run the two `install` lines again after a `git pull` that touches
`deploy/backup/`.

Schedule it nightly at 03:00, with output going to the journal
(`journalctl -t eduplatform-backup`):

```bash
sudo tee /etc/cron.d/eduplatform-backup >/dev/null <<'EOF'
# Nightly database + uploads backup: api-trainings.aztu.edu.az/deploy/backup
0 3 * * * root /usr/local/sbin/backup-eduplatform 2>&1 | logger -t eduplatform-backup
30 9 * * * root /usr/local/sbin/backup-eduplatform --check >/dev/null || logger -p user.err -t eduplatform-backup "STALE: no successful backup in the last 26 hours"
EOF
```

The file name has no dot on purpose: cron ignores files in `/etc/cron.d` whose
names contain one.

If you followed the old instructions, remove the crontab line they left behind:
`sudo crontab -u postgres -l`, then `sudo crontab -u postgres -e` and delete the
`backup-eduplatform.sh` line.

Take the first backup now and check it:

```bash
sudo backup-eduplatform
sudo backup-eduplatform --check     # OK: last successful backup finished 0h ago (...)
sudo ls -la /var/backups/eduplatform/db /var/backups/eduplatform/uploads
```

### What it keeps

- `db/eduplatform-<stamp>.dump`: `pg_dump` custom format. Each dump is checked
  with `pg_restore --list` before it counts.
- `uploads/<stamp>/`: a complete copy of `/opt/uploads`. Files that are unchanged
  since the previous night are hard links to it (`rsync --link-dest`), so the
  first snapshot costs the size of `/opt/uploads` and each later one costs only
  that day's new uploads. The procedure this replaces made a full `.tar.gz` every
  night and kept 14 of them, so with lesson videos of up to 512 MB each it could
  have filled the disk.
- 14 days of both, pruned by the date in the name, and only after a run that
  succeeded. A backup that keeps failing never deletes the good ones.

### Knowing that it works

On a host without mail, cron throws the output away, so a failing backup makes no
noise. The script writes `/var/backups/eduplatform/.last-success` at the very end
of a run that worked, and nowhere else:

- `backup-eduplatform --check` exits 1 once that is more than 26 hours old. The
  second cron line above logs an error when it does; point anything that watches
  the host's journal at it.
- For an alert that reaches a person, set a dead man's switch (healthchecks.io,
  or an Uptime Kuma push monitor) in `/etc/default/eduplatform-backup`:

  ```bash
  echo 'BACKUP_PING_URL=https://hc-ping.com/<your-check-uuid>' | sudo tee /etc/default/eduplatform-backup
  sudo chmod 600 /etc/default/eduplatform-backup
  ```

  The script calls it after every successful run, and the monitor alerts when the
  calls stop arriving. That covers a broken script, a full disk and a dead cron
  too.

### Copy it off this server

A backup on the same disk as the database survives only the failures that were
never going to hurt you. Pull the copies from a second machine rather than
pushing them from here. A server that can push can also overwrite and delete, so
an intruder here, or a mistaken `rm -rf` here, would reach the off-host copies
as well.

On **this server**, create a login that can only read `/var/backups/eduplatform`
over rsync:

```bash
sudo useradd --system --create-home --shell /bin/sh --groups eduplatform-backup backup-pull
sudo install -d -o backup-pull -g backup-pull -m 700 /home/backup-pull/.ssh
# One line: the forced command, then the backup host's public key.
echo 'restrict,command="/usr/bin/rrsync -ro /var/backups/eduplatform" ssh-ed25519 AAAA... backup@backup-host' \
  | sudo tee /home/backup-pull/.ssh/authorized_keys
sudo chown backup-pull:backup-pull /home/backup-pull/.ssh/authorized_keys
sudo chmod 600 /home/backup-pull/.ssh/authorized_keys
```

`rrsync -ro` (shipped with Ubuntu's `rsync` package) turns that key into
read-only rsync inside that one directory: no shell, no writes, no paths outside
it. If SSH is limited by `AllowUsers`/`AllowGroups` in `sshd_config`, or by UFW
rules for port 22, allow `backup-pull` from the backup host's address.

On the **backup host** (any Linux machine off this server with the disk space):

```bash
sudo apt install -y rsync postgresql-client   # pg_restore, to check each dump that arrives
sudo install -o root -g root -m 0755 pull-eduplatform-backup.sh /usr/local/sbin/pull-eduplatform-backup
sudo install -d -m 700 /srv/backups/trainings
sudo ssh-keygen -t ed25519 -N '' -f /root/.ssh/trainings_backup   # its .pub goes into the line above
sudo tee /etc/default/pull-eduplatform-backup >/dev/null <<'EOF'
SOURCE=backup-pull@trainings.aztu.edu.az
RSYNC_RSH="ssh -i /root/.ssh/trainings_backup"
KEEP_DAYS=90
EOF
sudo pull-eduplatform-backup      # the first run asks you to accept the host key
sudo tee /etc/cron.d/pull-eduplatform-backup >/dev/null <<'EOF'
0 5 * * * root /usr/local/sbin/pull-eduplatform-backup 2>&1 | logger -t eduplatform-backup-pull
EOF
```

It pulls only the run that `.last-success` names, so it never copies a backup
that is still being written. It keeps its own 90 days and never deletes anything
because the server did: there is no `--delete` anywhere in it.
`pull-eduplatform-backup --check` is the same staleness check for this side.

### Restore

Drill it once on this server before you need it; an untested backup is not a
backup. The script stops the backend, renames the live database to
`eduplatform_pre_restore_<time>` instead of dropping it, restores into a fresh
database owned by `eduplatform` in a single transaction, swaps the uploads, gives
them back to `1001:1001`, starts the backend and waits for readiness. If the
restore fails part way, it puts the old database back and restarts the backend.
If the backend is not ready within 5 minutes, it exits non-zero and prints how to
go back.

```bash
ls /var/backups/eduplatform/db/ /var/backups/eduplatform/uploads/
sudo restore-eduplatform \
  --db      /var/backups/eduplatform/db/eduplatform-<stamp>.dump \
  --uploads /var/backups/eduplatform/uploads/<stamp>
# Type RESTORE to confirm. Afterwards it prints the two commands that delete the
# kept database and uploads directory, once you have checked the site.
```

Use the dump and the snapshot with the same `<stamp>`: the same run made them,
minutes apart. Restoring from the backup host works the same way once the two
files are copied back to this server. Leave out `--uploads` to restore only the
database, for example after a bad migration (see the rollback section of
[deployment.md](docs/operations/deployment.md#11-rollback)).

To practise without touching production, restore into a scratch database
instead:

```bash
sudo -u postgres createdb eduplatform_restore_test
# Root opens the dump (postgres cannot read the backup directory) and hands it over
# as stdin. A redirect rather than a pipe: pg_restore needs to seek in a
# custom-format dump when it restores entries in a different order from the file.
sudo sh -c 'runuser -u postgres -- pg_restore --exit-on-error -d eduplatform_restore_test \
  < /var/backups/eduplatform/db/<file>.dump'
sudo -u postgres dropdb eduplatform_restore_test
```

### Uploaded files are not in the dump

`pg_dump` captures the `media_files` **rows** (id, object key, MIME type, size,
SHA-256) but not the bytes. Those live on disk in `/opt/uploads`. Restoring only
the database therefore gives you a catalogue of covers and lesson videos that all
404, which is why the backup takes both in the same run.

The dump and the snapshot are still taken minutes apart, so they can disagree
slightly: a file uploaded between the two exists on disk with no row, or has a
row with no file. Neither breaks the application. An orphan file is inert, and a
row whose file is missing shows as a 404 on that one asset. It is not worth
solving with filesystem snapshots, but it is worth knowing before you are
mid-restore and counting files.

## 10. Sizing

`DB_POOL_MAX` defaults to 20 connections per backend instance; PostgreSQL's
default `max_connections` is 100. One or two instances is fine. Beyond that,
raise `max_connections` in `postgresql.conf` or lower `DB_POOL_MAX` — each
connection costs memory, so pooling smaller usually beats connecting more.

On a dedicated database server, `shared_buffers` at ~25% of RAM is the standard
starting point; the packaged default (128MB) is deliberately tiny.
