# DOCKER_DEV_GUIDE.md

How to run the full local dev environment. Written for Claude Code and
humans alike — every command in this doc can be copy-pasted as-is from
the repo root.

---

## TL;DR

```bash
make up              # start Oracle, Kafka, RabbitMQ, WireMock LLM stub
make wait            # block until all health checks pass (~90s on first run)
make migrate         # apply all Liquibase changelogs
make verify          # mvn clean verify (unit + ArchUnit tests)
make it              # mvn -P it verify (integration tests against the running stack)
make app             # mvn -pl app spring-boot:run
make down            # stop containers, KEEP volumes (data persists)
make reset           # stop containers AND wipe volumes (clean slate)
```

Without `make`:

```bash
docker compose -f infra/docker/docker-compose.yml up -d
docker compose -f infra/docker/docker-compose.yml ps
mvn -pl app -am clean install -DskipTests
mvn -pl app spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

---

## What the dev environment includes

| Container | Image | Ports | Purpose |
|---|---|---|---|
| `tfpm-oracle` | `gvenzl/oracle-free:23-slim-faststart` | 1521 | Both pools (legacy-read + app-write) point here |
| `tfpm-kafka` | `confluentinc/cp-kafka:7.7.0` | 9092 / 29092 | KRaft mode (no ZooKeeper) |
| `tfpm-rabbitmq` | `rabbitmq:3-management` | 5672 / 15672 | RabbitMQ + management console |
| `tfpm-llm-gateway` | `wiremock/wiremock:3.9.2` | 8089 | Stubs the JPMC internal LLM gateway |

Sidecars (libpostal, swift-crf gRPC services) are **not** started by
default — they need real model files which are out of scope for this
repo. Run with `LIBPOSTAL_ENABLED=false SWIFT_CRF_ENABLED=false LLM_ENABLED=true`
for first-run sanity. See "Adding sidecars locally" below.

---

## First run, step by step

```bash
# 1. Verify Docker is running (Docker Desktop on Mac/Win, dockerd on Linux)
docker info > /dev/null || (echo "Docker not running"; exit 1)

# 2. Pull images ahead of time (optional but speeds first 'make up')
docker compose -f infra/docker/docker-compose.yml pull

# 3. Start everything in the background
make up
# Equivalent: docker compose -f infra/docker/docker-compose.yml up -d

# 4. Watch readiness — Oracle is the slow one (~60-90s first time)
make wait
# Equivalent: ./scripts/dev/wait-for-services.sh

# 5. Apply schema migrations
make migrate
# Equivalent: mvn -pl app liquibase:update -Dliquibase.url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 \
#                                          -Dliquibase.username=system \
#                                          -Dliquibase.password=oracle

# 6. Verify the schema landed
make oracle-shell
# Inside SQL*Plus:
SQL> SELECT table_name FROM all_tables WHERE owner = 'TFPM_ADDR_ENRICH' ORDER BY 1;
# Should list: ACCURACY_SAMPLES, AUDIT_LOG, EXCEPTION_QUEUE, FIELD_ATTRIBUTIONS,
#              IDEMPOTENCY_KEYS, STRUCTURING_RESULTS, VALIDATION_FEEDBACK
```

---

## Health checks (when is the env actually ready?)

`make up` returns the moment containers start; that's not the same as
ready-to-accept-connections. Use `make wait` (or its underlying script)
to block on real readiness:

```bash
./scripts/dev/wait-for-services.sh
```

What it checks:

- **Oracle**: connects with sqlplus and runs `SELECT 1 FROM dual`. Retries
  every 3 seconds for up to 5 minutes (Oracle Free first-boot is slow).
- **Kafka**: `kafka-broker-api-versions --bootstrap-server localhost:29092`
  returns OK.
- **RabbitMQ**: TCP connect to port 5672.
- **WireMock**: `GET http://localhost:8089/__admin/health` returns 200.

Exit code 0 means all four are ready. Anything else means investigate
container logs (`make logs <service>`).

---

## Running the app against the local env

```bash
# Build everything once
mvn clean install -DskipTests

# Run the app — connects to local Oracle/Kafka/MQ/WireMock automatically
mvn -pl app spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"

# Or use the make shortcut
make app
```

The `local` profile in `application.yml` points all connections at the
docker-compose service names with their host-mapped ports. Verify with:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{...},"kafka":{...},"rabbit":{...}}}
```

Send a test address:

```bash
curl -X POST http://localhost:8080/api/v1/enrich \
  -H "Content-Type: application/json" \
  -d '{
    "address": "Office 1204, Tower 3, Sheikh Zayed Road, Dubai, UAE",
    "country_hint": "AE",
    "locale": "en-AE"
  }'
```

Expect a JSON response with `result_id`, per-field confidence, and
`source` showing which structurer contributed each field. With sidecars
disabled, `source` will be `llm` (via the WireMock stub) for everything
the stub recognises, and missing for everything else.

---

## Running integration tests against the local env

```bash
make it
# Equivalent: mvn -pl integration-tests -P it verify
```

Note: `MultiContainerIdempotencyTest` uses Testcontainers and brings up
its OWN Oracle/Kafka/MQ instances regardless of what docker-compose has
running. This is intentional — the test is hermetic. Other integration
tests use the docker-compose stack via `local` profile.

If Testcontainers conflicts with docker-compose containers on ports,
either stop docker-compose first (`make down`) or change the host port
mapping in `infra/docker/docker-compose.yml`.

---

## Useful Make targets

```
make help             # list all targets with descriptions
make up               # start docker-compose stack
make wait             # block until all services are healthy
make down             # stop containers, keep volumes
make reset            # stop containers, REMOVE volumes (data loss)
make logs             # tail all container logs
make logs SVC=oracle  # tail one container's logs
make oracle-shell     # open SQL*Plus inside the oracle container
make kafka-topics     # list kafka topics
make mq-console       # print URL for MQ web console
make wiremock-reload  # POST to wiremock /__admin/mappings/reset
make migrate          # apply Liquibase changelogs
make migrate-rollback N=1   # roll back N changesets
make build            # mvn clean install -DskipTests
make verify           # mvn clean verify (unit + ArchUnit)
make it               # mvn -P it verify (integration tests)
make accuracy         # mvn -P accuracy verify (accuracy harness)
make app              # spring-boot:run with local profile
make app-debug        # spring-boot:run with remote debug on 5005
make ports            # show which host ports are mapped to what
```

---

## Common issues and fixes

### Oracle never becomes ready

- First boot after `make reset` takes 60-120 seconds; subsequent boots
  are 10-30s.
- If it times out: `make logs SVC=oracle` and look for "DATABASE IS READY
  TO USE" — its absence usually means insufficient RAM (Oracle Free
  needs ~2GB).
- On Apple Silicon Macs, ensure Docker Desktop has Rosetta enabled and
  ≥4GB RAM allocated.

### `make migrate` fails with "ORA-01017: invalid username/password"

The migration runs as `system/oracle` against the bootstrap connection.
Verify the env override didn't leak: `unset ORACLE_APP_PASSWORD ORACLE_LEGACY_PASSWORD`
before running migrate.

### Kafka consumer can't reach broker from app

The compose file exposes Kafka on **two** listeners: `9092` (for
container-to-container, advertised as `kafka:9092`) and `29092` (for
host-to-container, advertised as `localhost:29092`). The app's `local`
profile uses `localhost:29092`. If you're running the app inside a
container, switch to `kafka:9092`.

### RabbitMQ container exits immediately

Check `docker logs tfpm-rabbitmq` for errors. Ensure no other process is
already bound to port 5672. The management console is available at
http://localhost:15672 (guest/guest).

### WireMock returns 404 for the LLM call

WireMock loads mappings from `infra/docker/wiremock/mappings/` at boot.
If you edited a mapping after `make up`, run `make wiremock-reload` or
restart just that container: `docker compose restart llm-gateway-stub`.

### Tests pass locally but fail in CI

The most common cause is a test that depends on docker-compose being
up. Tests should be hermetic — they should bring up their own
Testcontainers. If a test fails in CI but passes locally, suspect that.

---

## Adding sidecars locally

The libpostal and swift-crf sidecars are not in this repo because they
require real model files. To run them locally:

### libpostal (real implementation needed for production)

```bash
# 1. Build a sidecar image that wraps libpostal in a gRPC server
#    speaking proto/structurer.proto. There's no canonical Java
#    libpostal binding; typical solution is a Python or Go sidecar.

# 2. Once built, point the app at it:
LIBPOSTAL_ENABLED=true LIBPOSTAL_ENDPOINT=localhost:50051 make app
```

A reference Python sidecar template lives in `infra/docker/sidecars/libpostal-stub/`
(currently empty stub — implement when ready).

### SWIFT CRF (waiting on download from Swift)

Same pattern as libpostal once the model is downloaded and IS&C-cleared.
See `adapter-swift-crf/.../SwiftCrfAddressStructurer.java` Javadoc for
the activation checklist.

### LLM gateway (use WireMock for dev)

The `tfpm-llm-gateway` container in compose IS the dev LLM. WireMock
mappings in `infra/docker/wiremock/mappings/` define canned responses.
For real LLM calls in dev, point at OpenAI/Ollama/anthropic via the
`OpenAiCompatibleLlmClient`:

```bash
LLM_ENABLED=true \
LLM_PROVIDER=openai-compatible \
LLM_ENDPOINT=https://api.openai.com/v1 \
LLM_API_KEY=sk-... \
LLM_MODEL=gpt-4o-mini \
make app
```

---

## Tearing down

```bash
make down              # graceful stop, volumes preserved (resume with `make up`)
make reset             # stop AND remove volumes (Oracle re-init takes 60-120s next time)
docker system prune    # broader cleanup if disk is full
```

---

## What this dev env is NOT

- **Not a perf test environment.** Single Oracle instance with default
  config; expect 1/10th to 1/100th of production throughput.
- **Not a security test environment.** Default passwords, no TLS, no
  auth on internal services. Never expose ports outside the dev machine.
- **Not equivalent to UAT.** UAT uses real JPMC internal LLM gateway,
  real shared Oracle, real Kafka cluster. Expect behavioural differences
  particularly around network buffering (see
  `docs/LLM_MODEL_INTEGRATION.md` on ProxySG/ICAP).

For Claude Code: anything that works locally and passes
`make verify && make it` is a candidate for PR. Anything that requires
configuration only present in UAT/prod must be flagged in the PR
description so a human can validate against the right environment.
