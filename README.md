# Simple FTP → S3 Transfer

A small Java 21 + Spring Boot application for learning four file-transfer safeguards: completion markers, duplicate
prevention, large-file upload, and retry/status tracking. One application, PostgreSQL, FTP, and AWS S3; no SQS and no
separate workers.

## Requirements

- JDK 21
- Maven 3.9+
- Docker Desktop
- AWS account and an S3 bucket

## 1. Start PostgreSQL and demo FTP

```bash
cp .env.example .env
docker compose up -d
```

PostgreSQL uses host port 5433; FTP uses host port 2121. Check your FTP image's home-directory layout and passive-mode
settings if login or listing fails.

## 2. Completion-marker protocol

The producer should upload `orders.csv.part`, rename it to `orders.csv` after the transfer finishes, then create
`orders.csv.done`. The application ignores `.part` files and only processes a data file when its `.done` marker exists.
Marker files are a protocol: they are reliable only if the producer creates them after the data file is complete.

## 3. Configure AWS

Edit `.env` and set `S3_BUCKET` and `AWS_REGION`. Configure AWS credentials with an AWS profile, environment variables,
or an IAM role. Do not commit access keys.

```bash
aws sts get-caller-identity
```

## 4. Build and run

Spring Boot does not automatically read `.env`; load it into your shell:

```bash
set -a
source .env
set +a
mvn clean package
java -jar target/ftp-s3-simple-1.0.0.jar
```

## 5. Check status

- Health: http://localhost:8080/health
- Recent transfer jobs: http://localhost:8080/api/transfers

## How the safeguards work

1. **Incomplete files:** requires a matching `.done` marker.
2. **Duplicates:** PostgreSQL unique constraint on source path, size, and modified timestamp prevents repeated scans of
   the same observed version. For stronger identity, use a producer file ID or content hash.
3. **Large files:** FTP content streams to a temporary disk file rather than into a giant byte array; S3 Transfer
   Manager uploads the file and supports multipart transfer. Ensure temporary disk space is available.
4. **Reliability:** database job records, error text, retry counter, and retrying failed jobs.

## Demo limitations

This is an educational starting point, not production-hardened. It is designed for one app instance; add job
leases/atomic claiming before scaling out. A crash after S3 succeeds but before PostgreSQL records `SUCCESS` can cause
the same deterministic S3 key to be overwritten on retry. Add stale-`PROCESSING` recovery, exponential backoff, checksum
verification, monitoring, IAM least privilege, and incomplete multipart cleanup before production. Plain FTP is
unencrypted; prefer FTPS/SFTP where possible.
