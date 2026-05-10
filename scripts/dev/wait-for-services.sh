#!/usr/bin/env bash
#
# Block until every container in the dev stack is actually serving requests,
# not just "running". Returns 0 when all four services are healthy, non-zero
# on timeout (default 5 minutes total).
#
# Used by `make wait` and CI smoke tests.

set -euo pipefail

TIMEOUT_SECS="${TIMEOUT_SECS:-300}"
SLEEP_SECS=3
COMPOSE="docker compose -f infra/docker/docker-compose.yml"

cd "$(git rev-parse --show-toplevel 2>/dev/null || dirname "$0"/../..)"

deadline=$(( $(date +%s) + TIMEOUT_SECS ))
log() { printf '[wait] %s\n' "$*"; }

# ----------------------------------------
# Oracle: connect with sqlplus, run trivial query
# ----------------------------------------
wait_oracle() {
  log "waiting for Oracle (this can take 60-120s on first boot)..."
  while (( $(date +%s) < deadline )); do
    if $COMPOSE exec -T oracle bash -c \
         'echo "SELECT 1 FROM dual;" | sqlplus -s system/oracle@FREEPDB1' \
         2>/dev/null | grep -q '^         1' ; then
      log "Oracle ✓"
      return 0
    fi
    sleep "$SLEEP_SECS"
  done
  log "Oracle ✗ (timed out)"
  return 1
}

# ----------------------------------------
# Kafka: bootstrap-server responds
# ----------------------------------------
wait_kafka() {
  log "waiting for Kafka..."
  while (( $(date +%s) < deadline )); do
    if $COMPOSE exec -T kafka kafka-broker-api-versions \
         --bootstrap-server localhost:9092 > /dev/null 2>&1 ; then
      log "Kafka ✓"
      return 0
    fi
    sleep "$SLEEP_SECS"
  done
  log "Kafka ✗ (timed out)"
  return 1
}

# ----------------------------------------
# IBM MQ: TCP connect to 1414
# ----------------------------------------
wait_mq() {
  log "waiting for IBM MQ..."
  while (( $(date +%s) < deadline )); do
    if (echo > /dev/tcp/localhost/1414) > /dev/null 2>&1 ; then
      log "IBM MQ ✓"
      return 0
    fi
    sleep "$SLEEP_SECS"
  done
  log "IBM MQ ✗ (timed out)"
  return 1
}

# ----------------------------------------
# WireMock: admin endpoint 200
# ----------------------------------------
wait_wiremock() {
  log "waiting for WireMock LLM stub..."
  while (( $(date +%s) < deadline )); do
    if curl -fsS http://localhost:8089/__admin/health > /dev/null 2>&1 ; then
      log "WireMock ✓"
      return 0
    fi
    sleep "$SLEEP_SECS"
  done
  log "WireMock ✗ (timed out)"
  return 1
}

wait_oracle
wait_kafka
wait_mq
wait_wiremock

log "All services ready. Run 'make migrate' next."
