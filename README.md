# FTP → S3 Transfer (Java 21 + Spring Boot)

A small, single-application example that polls an FTP directory, accepts only files with completion markers, stores job
state in PostgreSQL, downloads files to disk using streams, and uploads files to Amazon S3 with the AWS SDK for Java 2.x
Transfer Manager.

The example includes:

- `.part` / `.done` producer protocol checks.
- PostgreSQL uniqueness for each observed source version and atomic claiming of `PENDING` jobs.
- Deterministic, version-specific S3 keys and SHA-256 metadata to reconcile an S3 success that happened before a
  database update/crash.
- Temporary-file streaming rather than loading a whole file into Java heap.
- S3 Transfer Manager multipart support using the Java async S3 client.
- Retry backoff, retry limits, stale `PROCESSING` recovery, status endpoint, and temp-file cleanup.
- Docker Compose for PostgreSQL, demo FTP, and the Spring Boot app.

This is a learning baseline, not a fully hardened multi-replica production service. It intentionally assumes one app
instance. For multi-instance deployment, add a proper distributed lease/heartbeat or queue, and use FTPS/SFTP instead of
plain FTP.

## Requirements

- Docker Desktop + Docker Compose
- AWS CLI configured with an AWS profile, or an AWS IAM role when running on AWS
- An S3 bucket in the region configured below

You do not need to install Java or Maven on your host when using Docker Compose. For running the app directly on your
host, install JDK 21 and Maven 3.9+.

## 1. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and set `S3_BUCKET` and `AWS_REGION`. Change the local demo passwords too. Configure AWS credentials with
`aws configure --profile default` (or set `AWS_PROFILE` to a configured profile). Never commit `.env` or AWS
credentials.

Verify your credentials on the host:

```bash
aws sts get-caller-identity --profile default
```

When using Compose, `.env` is read by Docker Compose and its values are passed to the app container. You do **not** need
`set -a; source .env; set +a` for the Compose workflow. The app container mounts `~/.aws` read-only so the AWS SDK can
use your local profile. On EC2, prefer an attached IAM role instead of mounting local credentials.

## 2. Start the full stack

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

Endpoints:

- Health: http://localhost:8080/health
- Recent transfer jobs: http://localhost:8080/api/transfers
- FTP host endpoint: `localhost:2121`
- PostgreSQL host port: `localhost:5433`

The demo FTP user's upload directory is mounted at `ftp-data/testuser/outgoing/` in this repository. Place test files
there. The exact home-directory layout can vary with the FTP server image; verify it if you replace the image.

## 3. Completion-marker protocol

The producer must follow this protocol:

1. Create `report.csv.part` while the file is being uploaded.
2. Upload the data under a temporary name (recommended: `report.csv.uploading`).
3. After the transfer has fully completed, rename the data to `report.csv`.
4. Create `report.csv.done` only after the final file is complete.
5. Remove `report.csv.part` after the final name and `.done` marker are in place, according to a coordinated
   producer/consumer protocol.

The consumer ignores `.part` and `.done` files themselves, skips a data file if its `.part` marker exists, and requires
a matching `.done` marker. It also checks the marker before and after download and verifies the downloaded byte count
against the FTP listing.

**Important:** the marker protocol is only reliable if the producer obeys it. The strongest common pattern is
upload-to-temporary-name, rename to final name, then create `.done`. FTP rename atomicity depends on the
server/filesystem.

Example files:

```text
ftp-data/testuser/outgoing/report.csv
ftp-data/testuser/outgoing/report.csv.done
```

Do not leave `report.csv.part` in place once the file is ready for processing.

## 4. Duplicate prevention and S3 keys

The database has a unique constraint on `(source_path, source_size, source_modified_at)`. The repository claims a new or
`PENDING` row with one atomic SQL statement, so repeated scans do not start a second transfer for a source version
already `PROCESSING` or `SUCCESS`.

The S3 key contains a SHA-256 version identifier derived from the source path, size, and FTP modification timestamp, for
example:

```text
ftp-imports/<source-version-hash>/report.csv
```

This avoids an older retry overwriting a newer version of the same filename. After download, the app calculates the
content SHA-256 and stores it as S3 object metadata and in PostgreSQL. If a retry finds the expected object and matching
metadata, it records success without uploading the bytes again.

If your producer can supply a globally unique file ID or manifest checksum, prefer that over relying on FTP modification
timestamps for source-version identity.

## 5. Large files

The app streams FTP data into a randomly named temporary file under `TRANSFER_TEMP_DIRECTORY`; it does not allocate a
`byte[]` for the whole file. After validating the size, it calculates SHA-256 and passes the `Path` to the AWS SDK S3
Transfer Manager. The Java async S3 client is configured for multipart support.

Make sure the temp volume has enough free disk space for at least one full file per concurrent transfer. This example
processes files sequentially, which keeps resource use simple. Temporary files are removed in `finally`, including after
transfer failures.

## 6. Retry and crash recovery

- Failed jobs store an error message and increment `retry_count`.
- Retry delay uses exponential backoff, capped at one hour.
- `TRANSFER_MAX_RETRIES` limits automatic retries.
- Old `PROCESSING` rows are returned to `PENDING` after `TRANSFER_STALE_PROCESSING_MINUTES`, allowing recovery after a
  container/process crash.
- `/api/transfers` shows recent job state and errors.

S3 and PostgreSQL cannot share one atomic transaction. The version-specific S3 key and SHA-256 metadata make a retry
after “S3 succeeded, DB update failed” recoverable. This does not guarantee exactly-once execution across systems; it
makes the workflow idempotent and repairable.

## 7. Run directly on your host (optional)

Start only PostgreSQL and FTP:

```bash
docker compose up -d postgres ftp
```

Then, from the project root, load `.env` for a host-launched Java process and run the app:

```bash
set -a
source .env
set +a
mvn spring-boot:run
```

For this optional host mode, `.env` uses `DB_URL=jdbc:postgresql://localhost:5433/transferdb` and `FTP_HOST=localhost` (
set `FTP_HOST=localhost` if you changed it). Spring Boot does not automatically parse a `.env` file when started
directly from Maven; these shell commands export the variables to the Java process.

Do not run the Compose `app` service and a host-launched app at the same time unless you intentionally want two pollers;
this starter is designed for one app instance.

## 8. Useful commands

```bash
# Recent transfer state
curl http://localhost:8080/api/transfers

# Logs
docker compose logs -f app

# Stop containers without deleting persisted PostgreSQL state
docker compose down

# Destructive: also deletes PostgreSQL and temp volumes
# docker compose down -v
```

## Production hardening still needed

- Use FTPS/SFTP rather than plain FTP.
- Use IAM roles and least-privilege bucket access; do not bake credentials into images.
- Use a distributed lease/heartbeat or a queue before running multiple app replicas.
- Add integration tests against a test database and S3-compatible test service.
- Add disk-capacity monitoring, structured metrics/alerts, and retention policies.
- Configure S3 lifecycle cleanup for abandoned multipart uploads.
- If strict content integrity is required, have the producer publish a checksum manifest and compare it after download.
