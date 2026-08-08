# Deployment and Recovery

## Runtime topology

The current single-host deployment runs PostgreSQL/pgvector, Redis, MinIO, Web, and optional model/parser
sidecars with Docker Compose. `rag-api` and `rag-worker` run as persistent user-systemd services from the packaged
Spring Boot JARs. PostgreSQL remains the source of truth; Redis is disposable coordination and short-memory state.

The public development URL is exposed through the host's existing FRPC installation. It is intentionally separate
from application lifecycle management: restarting API, Worker, or Web does not rewrite the FRPC server mapping.
The current FRPC endpoint is HTTP-only and must not be treated as a production TLS boundary.

## Build and install

Create `.env` from `.env.example`, replace every development credential, and restrict it to the service owner:

```bash
chmod 600 .env
./mvnw test
docker compose config --quiet
docker compose up -d postgres redis minio minio-init
docker compose --profile models up -d model-sidecar
./scripts/install-user-services.sh
./scripts/deploy-local.sh
```

The service templates live in `deploy/systemd`. They use `ProtectSystem=strict`, `NoNewPrivileges`, a private
temporary directory, explicit writable paths, and graceful shutdown. Enable user lingering at the host level when
the services must survive logout.

`deploy-local.sh` packages API and Worker unless `--skip-build` is supplied, copies both JARs into one immutable
`.runtime/releases/<release-id>` directory, atomically switches `.runtime/current`, reinstalls the unit templates,
restarts both processes, and waits for API health. Never point a running Spring Boot process directly at a Maven
`target` JAR: a later package can replace that archive while the old process still needs it during graceful shutdown.

## Health and diagnostics

```bash
systemctl --user show rag-api.service rag-worker.service \
  --property=Id --property=ActiveState --property=SubState --property=NRestarts
curl --fail http://127.0.0.1:8080/actuator/health
docker compose ps
docker exec rag-platform-redis-1 redis-cli TTL rag:ingestion
docker exec rag-platform-redis-1 redis-cli XINFO GROUPS rag:ingestion
```

For a healthy ingestion transport, the Stream TTL is `-1`, group `pending` returns to zero after work completes,
and `lag` does not grow indefinitely. `RAG_INGESTION_CLAIM_IDLE_MS` controls when another Worker may claim an
abandoned Pending entry. Keep `RAG_INGESTION_HEARTBEAT_INTERVAL_SECONDS` comfortably below
`RAG_INGESTION_STALE_AFTER_SECONDS`; the defaults are 30 and 300 seconds. The latter is a Worker lease timeout,
not a request or Agent deadline.

S3 calls use separate connection, per-attempt, and total request limits through
`RAG_STORAGE_CONNECT_TIMEOUT_SECONDS`, `RAG_STORAGE_ATTEMPT_TIMEOUT_SECONDS`, and
`RAG_STORAGE_REQUEST_TIMEOUT_SECONDS`. A storage timeout fails the current ingestion stage and uses the persisted
retry path; it never publishes a partially indexed document version.

`/actuator/health` and `/actuator/info` are public locally. Prometheus and metrics endpoints require a valid JWT.
The production browser Smoke test logs in through the public URL and checks KnowledgeOps, Chat filters, Memory,
and Evaluation at the desktop viewport:

```bash
RAG_SMOKE_BASE_URL=https://rag.example.com \
RAG_SMOKE_USERNAME=admin \
RAG_SMOKE_PASSWORD='...' \
npm --prefix web run smoke:production
```

The bootstrap administrator can create `ADMIN`, `EDITOR`, and `VIEWER` members in `/team`. A role, enabled-state, or
password change revokes the member's existing JWTs through `app_user.auth_version`. Deploying Flyway V17 introduces
this claim, so browser sessions issued by a pre-V17 API intentionally require a fresh login after deployment.
Usernames are case-insensitively unique within the organization, and member rows are disabled rather than deleted to
preserve ownership and audit references.

## Credential master-key rotation

The credential cipher uses `v2:<key-id>:<nonce>:<ciphertext>` envelopes and can still decrypt legacy `v1` values.
The API and Worker must receive the same active key and fallback keyring. Never replace
`RAG_CREDENTIAL_MASTER_KEY` without retaining the previous key in `RAG_CREDENTIAL_DECRYPTION_KEYS` first.

### Upgrading a database that ran V42

The public V43 migration removes the temporary plaintext `model_profile.api_key` column. It intentionally stops
instead of dropping the column when any value is present. In a controlled maintenance window, first identify the
affected model Profiles and make sure their credentials can be recovered from the original provider or secret
manager. Clear the plaintext values, apply V43, and then re-enter each credential through the administration UI so
it is written as an AES-GCM envelope. Never copy plaintext bytes into `encrypted_api_key`, and take a verified
business backup before modifying an affected database.

Create and verify a business backup, prepare a new keyring, then restart API and Worker:

```bash
./scripts/backup.sh tmp/backups/rag-before-key-rotation
./scripts/verify-backup.sh tmp/backups/rag-before-key-rotation
./scripts/credential-keyring.sh prepare k2026_07
./scripts/deploy-local.sh
```

Sign in as an administrator and open `/security`. `unreadableCredentials` must be zero. The **重新加密** action locks
the model Profile, Evaluation schedule, and notification-delivery credential stores; it decrypts every value before
the first update and re-encrypts stale values in one PostgreSQL transaction. A failed preflight rolls back the entire
operation. The response and audit record contain only Key IDs and counts.

After the page reports zero waiting and zero unreadable credentials, save a freshly authenticated status response and
remove the fallback keys:

```bash
curl --fail --silent \
  -H "Authorization: Bearer ${RAG_ADMIN_ACCESS_TOKEN}" \
  http://127.0.0.1:8080/api/v1/security/credential-rotation \
  > tmp/credential-rotation-status.json
./scripts/credential-keyring.sh finalize tmp/credential-rotation-status.json
./scripts/deploy-local.sh --skip-build
```

Validate model Profile connectivity and any enabled Evaluation webhook after the second restart. Before the database
rotation action, the private `.env` backup printed by `prepare` can restore the original runtime configuration. After
database rotation begins, keep the new active key and loaded fallbacks until the status endpoint is clean; reverting
to an old-key-only environment would make newly encrypted values unreadable.

## Observability

The optional `observability` Compose profile runs OpenTelemetry Collector, Prometheus, Tempo, Alertmanager, and
Grafana. API and Worker send OTLP metrics and traces to the Collector; Prometheus scrapes the Collector's translated
application metrics and each stack component; Tempo stores traces and produces span metrics; Grafana provisions the
`RAG Platform Overview` dashboard from Git. Alertmanager currently uses a local console receiver so an external
notification destination is never contacted implicitly.

Start, validate, smoke-test, and stop the stack with:

```bash
./scripts/validate-observability.sh
./scripts/start-observability.sh
./scripts/observability-smoke.sh
./scripts/stop-observability.sh
```

`start-observability.sh` refuses the example Grafana password and requires both metric and trace export to be
enabled. The API and Worker can continue running when this profile is stopped; telemetry export is non-authoritative
and PostgreSQL remains the source of business state.

The local endpoints are:

| Service | URL | Purpose |
|---|---|---|
| Grafana | `http://127.0.0.1:3000` | Provisioned metrics and trace dashboard |
| Prometheus | `http://127.0.0.1:9090` | PromQL and target diagnostics |
| Alertmanager | `http://127.0.0.1:9093` | Local alert state |
| Tempo | `http://127.0.0.1:3200` | Trace API and readiness |
| OTel Collector | `127.0.0.1:4317`, `127.0.0.1:4318` | OTLP gRPC and HTTP ingestion |

Every host binding above is loopback-only and `observability-smoke.sh` fails if a management port binds to
`0.0.0.0` or `::`. The existing FRPC route exposes only the RAG application. For administration from another
machine, use an authenticated SSH tunnel instead of adding these ports to FRPC:

```bash
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 user@rag-host
```

Micrometer emits timers such as `rag_run_milliseconds`, `rag_retrieval_milliseconds`,
`rag_model_request_milliseconds`, `rag_agent_stage_milliseconds`, and `rag_ingestion_stage_milliseconds`, with
matching counters such as `rag_run_total`. Model usage records input/output Token counters and cost only when a
Profile defines per-million-token prices. Tags are intentionally low-cardinality and never include queries,
document text, user IDs, API keys, or Run IDs.

OTLP export is off by default in application configuration. Enable only the signals accepted by the local Collector:

```text
RAG_OTLP_TRACING_ENABLED=true
RAG_OTLP_METRICS_ENABLED=true
RAG_OTLP_LOGGING_ENABLED=false
RAG_TRACE_SAMPLE_PROBABILITY=0.1
RAG_OTLP_METRICS_STEP=15s
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://127.0.0.1:4318/v1/metrics
```

The dashboard covers service presence, stack health, Run throughput and latency, retrieval/model/Agent/ingestion
latency, Token usage, JVM heap, database pool state, HTTP traffic, and error counters. Prometheus rules detect
missing API/Worker telemetry, Collector loss, Run/model errors, ingestion failures, sustained heap pressure, and
database-pool waiting. These are intentionally observational alerts, not hard quality requirements or release
blockers. Alert thresholds should be tuned from actual workload history before adding an off-host receiver.

Prometheus retains up to 15 days or 10 GB. Tempo, Prometheus, Alertmanager, and Grafana use named Docker volumes,
but their contents are derived operational data and are excluded from the business backup. Dashboard, datasource,
alert, and Collector definitions are versioned under `deploy/observability`, so the control plane is reproducible.
PostgreSQL and MinIO remain the required backup sources.

## Evaluation notifications

An Evaluation schedule can send one webhook after its linked Fast and Deep Runs both reach terminal states. The
endpoint must use HTTP or HTTPS, and its signing secret is encrypted with the same credential cipher used for model
Profiles. The default network policy rejects loopback, private, link-local, multicast, and IPv6 unique-local targets;
leave `RAG_EVALUATION_NOTIFICATION_ALLOW_PRIVATE_ADDRESSES=false` outside an isolated development environment.

The receiver should verify `X-RAG-Signature` as `v1=<lowercase hexadecimal HMAC-SHA256>` over
`X-RAG-Timestamp + "." + rawRequestBody`. It should also reject stale timestamps and deduplicate
`X-RAG-Idempotency-Key`; a delivery can be retried after an ambiguous network failure. Event and delivery identifiers
are supplied in `X-RAG-Event` and `X-RAG-Delivery`. The stable payload schema is
`rag.evaluation.notification/v1`.

`RAG_EVALUATION_NOTIFICATION_POLL_MS` controls dispatcher polling,
`RAG_EVALUATION_NOTIFICATION_STALE_AFTER_SECONDS` reclaims an interrupted delivery, and the connect/request timeout
settings bound a single outbound attempt. These limits do not impose a timeout on Fast RAG, Deep RAG, or final answer
generation. Delivery retries are persisted and stop after five attempts; notification failure never changes the
linked comparison result.

## Backup

The backup script creates a PostgreSQL custom-format dump, mirrors all objects from the configured MinIO bucket,
writes the Flyway version to a manifest, and generates SHA-256 checksums. The destination is private by default.

```bash
./scripts/backup.sh tmp/backups/rag-release
./scripts/verify-backup.sh tmp/backups/rag-release
```

Copy completed archives to encrypted off-host storage and apply an external retention policy. A local archive is
not protection against host or volume loss.

## Restore drill

Run every drill against a new disposable database name. The script refuses the configured production database,
refuses an existing target, validates checksums before restore, checks Flyway/document state, and drops the drill
database unless explicitly retained.

```bash
./scripts/restore-drill.sh tmp/backups/rag-release rag_restore_drill
```

This drill restores PostgreSQL and checksum-validates the MinIO archive; it does not overwrite the live bucket.
Object recovery must first target an empty recovery bucket with `mc mirror`, be compared with the manifest/archive,
and only then be promoted during a declared maintenance window.

## Release safety

Before a schema or model-generation change, create and verify a backup. Keep the previous JARs/images and active
Index Generation until the new release passes health, public Smoke, Fast/Deep RAG, citation, and effective-version
checks. A vector rollback activates a retained Generation; it must not rewrite vectors in place.

Rotate bootstrap administrator, MinIO, JWT, model-encryption, and model-provider credentials before production use.
Terminate HTTPS before exposing authentication or document content to untrusted networks, and restrict PostgreSQL,
Redis, MinIO API/console, model sidecars, and Actuator metrics from public ingress.
