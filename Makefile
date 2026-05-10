# Makefile for TFPM Address Enrichment local dev.
#
# All targets work from the repo root and assume:
#   - Docker (with compose plugin) is installed and running
#   - Maven 3.9+ is on PATH
#   - JDK 21 is the active Java
#
# See docs/DOCKER_DEV_GUIDE.md for full explanation.

COMPOSE := docker compose -f infra/docker/docker-compose.yml
ORACLE_URL := jdbc:oracle:thin:@//localhost:1521/FREEPDB1
ORACLE_USER := system
ORACLE_PASS := oracle

.PHONY: help up wait down reset logs oracle-shell kafka-topics rabbitmq-console wiremock-reload \
        migrate migrate-rollback build verify it accuracy app app-debug ports clean

help:
	@echo ""
	@echo "TFPM Address Enrichment — local dev"
	@echo ""
	@echo "Environment:"
	@echo "  make up               Start docker-compose stack (Oracle, Kafka, RabbitMQ, WireMock)"
	@echo "  make wait             Block until all services are healthy"
	@echo "  make down             Stop containers (keep volumes)"
	@echo "  make reset            Stop containers AND remove volumes (clean slate)"
	@echo "  make logs [SVC=name]  Tail logs (all, or one service)"
	@echo "  make ports            Show host port mappings"
	@echo ""
	@echo "Database:"
	@echo "  make migrate          Apply Liquibase changelogs"
	@echo "  make migrate-rollback N=1   Roll back N changesets"
	@echo "  make oracle-shell     Open SQL*Plus inside the oracle container"
	@echo ""
	@echo "Services:"
	@echo "  make kafka-topics     List Kafka topics"
	@echo "  make rabbitmq-console  Print RabbitMQ management URL"
	@echo "  make wiremock-reload  Reload WireMock LLM stub mappings"
	@echo ""
	@echo "Build & test:"
	@echo "  make build            mvn clean install -DskipTests"
	@echo "  make verify           mvn clean verify (unit + ArchUnit)"
	@echo "  make it               mvn -P it verify (integration tests)"
	@echo "  make accuracy         mvn -P accuracy verify (accuracy harness)"
	@echo ""
	@echo "Run:"
	@echo "  make app              Start the app locally (outside Docker)"
	@echo "  make app-debug        Start the app with remote debug on 5005"
	@echo "  make docker-app       Build and run the app as a Docker container"
	@echo ""

# ============================================================
# Docker stack
# ============================================================

up:
	$(COMPOSE) up -d
	@echo ""
	@echo "Started. Run 'make wait' to block until services are ready."

wait:
	@./scripts/dev/wait-for-services.sh

down:
	$(COMPOSE) down

reset:
	$(COMPOSE) down -v
	@echo "Volumes removed. Next 'make up' will re-init Oracle (60-120s)."

logs:
ifdef SVC
	$(COMPOSE) logs -f $(SVC)
else
	$(COMPOSE) logs -f
endif

ports:
	@$(COMPOSE) ps --format "table {{.Service}}\t{{.Ports}}"

# ============================================================
# Database
# ============================================================

migrate:
	mvn -pl app liquibase:update \
		-Dliquibase.url=$(ORACLE_URL) \
		-Dliquibase.username=$(ORACLE_USER) \
		-Dliquibase.password=$(ORACLE_PASS) \
		-Dliquibase.changeLogFile=infra/liquibase/changelog-master.xml

migrate-rollback:
	@if [ -z "$(N)" ]; then echo "Usage: make migrate-rollback N=1"; exit 1; fi
	mvn -pl app liquibase:rollback \
		-Dliquibase.url=$(ORACLE_URL) \
		-Dliquibase.username=$(ORACLE_USER) \
		-Dliquibase.password=$(ORACLE_PASS) \
		-Dliquibase.rollbackCount=$(N)

oracle-shell:
	$(COMPOSE) exec oracle sqlplus $(ORACLE_USER)/$(ORACLE_PASS)@FREEPDB1

# ============================================================
# Service helpers
# ============================================================

kafka-topics:
	$(COMPOSE) exec kafka kafka-topics --bootstrap-server localhost:9092 --list

rabbitmq-console:
	@echo "RabbitMQ management:  http://localhost:15672"
	@echo "Login:                guest / guest"

wiremock-reload:
	curl -fsS -X POST http://localhost:8089/__admin/mappings/reset
	@echo "WireMock mappings reloaded from infra/docker/wiremock/mappings/"

# ============================================================
# Build & test
# ============================================================

build:
	mvn clean install -DskipTests

verify:
	mvn clean verify

it:
	mvn -pl integration-tests -am -P it verify

accuracy:
	mvn -P accuracy verify

# ============================================================
# Run
# ============================================================

app:
	mvn -pl app -am spring-boot:run \
		-Dspring-boot.run.arguments="--spring.profiles.active=local"

app-debug:
	mvn -pl app -am spring-boot:run \
		-Dspring-boot.run.arguments="--spring.profiles.active=local" \
		-Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
	@echo "Remote debug listening on :5005"

docker-app: build
	$(COMPOSE) --profile app up -d --build app
	@echo "App running at http://localhost:8080"

clean:
	mvn clean
	@echo "Maven targets cleaned. Docker volumes intact (use 'make reset' for those)."
