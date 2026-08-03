# SA-AgentLink

> A vendor-neutral collaboration runtime for Codex, Gemini CLI, Claude Code, Google Antigravity, and remote A2A agents.

[![CI](https://github.com/WarmHam04/sa-agentlink/actions/workflows/ci.yml/badge.svg)](https://github.com/WarmHam04/sa-agentlink/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00.svg)](https://adoptium.net/)

SA-AgentLink lets independently deployed or locally installed agents collaborate without sharing private prompts, memory, or tool implementations. A coordinator submits a task graph; AgentLink discovers suitable agents, executes independent branches concurrently, passes upstream Artifacts into dependent tasks, and streams normalized progress events to clients.

The project deliberately separates two layers:

- **A2A semantic layer** — AgentCard, Skill, Task, Artifact, Context, state transitions, and event ordering.
- **Runtime adapter layer** — translates those semantics into Codex JSONL, Gemini stream-json, Claude Code stream-json, Antigravity text mode, or remote A2A HTTP.

This prevents orchestration logic from depending on one vendor's session format or CLI flags.

## Why this is different from sub-agents inside one coding tool

A built-in sub-agent usually lives inside one product and inherits its runtime, authentication, and conversation model. AgentLink treats each runtime as an opaque peer:

```text
                            +------------------+
                            |   Coordinator    |
                            +---------+--------+
                                      |
                                submit Task DAG
                                      |
                         +------------v------------+
                         |       SA-AgentLink      |
                         | discovery / state / SSE |
                         +--+----------+----------++
                            |          |          |
                    JSONL   |  JSONL   | JSONL    | A2A HTTP
                     +------v-+  +-----v----+ +---v------+  +-------------+
                     | Codex  |  | Gemini   | | Claude   |  | Remote A2A  |
                     | CLI    |  | / agy    | | Code     |  | Agent       |
                     +--------+  +----------+ +----------+  +-------------+
```

Agents exchange Messages, task status, and Artifacts. They do not need access to another agent's internal chain of thought, memory store, or tools.

## Implemented capabilities

- AgentCard/Skill registration and routing by capability, health, load, or explicit `agentId`.
- Validated Task DAGs with cycle detection, parallel fan-out, dependency barriers, failure propagation, and Artifact injection.
- Context-level idempotency: replaying the same definition with the same `contextId` returns the existing workflow.
- Task state constraints and immutable terminal states.
- SSE TaskEvent stream with monotonic offsets and replay after reconnect.
- Optional PostgreSQL snapshots, durable event replay, restart recovery, and an at-least-once transactional Outbox.
- Normalized Task index for exact Task lookup, snapshot compare-and-set updates, and fenced PostgreSQL execution leases that prevent two coordinators from owning the same Workflow.
- PostgreSQL-backed cluster Agent Registry with TTL heartbeats, stale-entry pruning, revision tracking, Skill indexes, and node-local routing for process/local adapters.
- Runtime Adapter SPI with safe argument lists—no shell command interpolation.
- Safe defaults: Codex read-only sandbox, Gemini/Claude/agy plan mode, workspace-root confinement, process timeout, and bounded Artifact size.
- Bounded retries with exponential backoff (`maxAttempts`, 1–5).
- Remote A2A HTTP invocation, Agent Card discovery, inbound A2A endpoints, outbound SSE streaming, W3C trace-context propagation, and remote Task cancellation.
- A2A blocking/immediate send, SSE streaming, Task lookup/cancel, plus cancel propagation to local CLI processes.
- Prometheus metrics and Spring Boot health probes.
- OpenTelemetry Workflow/Task spans with runtime events, Agent attributes, failure status, lease-loss events, and OTLP-compatible exporters.
- Optional OAuth2 Resource Server mode with JWT/OIDC validation and operation-specific scopes.
- At-least-once Webhook, Kafka, and RocketMQ Outbox publishers with multi-node claim ownership and retry diagnostics.
- SSRF protection for remote Agent endpoints; private networks are denied by default.

## Runtime support

| Runtime | Transport | Progress | Default permission | Status |
|---|---|---|---|---|
| OpenAI Codex CLI | `codex exec --json` | JSONL | `read-only` | Implemented |
| Google Gemini CLI | `--output-format stream-json` | JSONL | `plan` | Implemented |
| Anthropic Claude Code | `--output-format stream-json` | JSONL | `plan` | Implemented |
| Google Antigravity `agy` | `--print` | final text | `plan` | Implemented, limited streaming |
| Remote A2A agent | HTTP `message:send` / `message:stream` | final + SSE events | AgentCard-defined | Inbound + outbound implemented |
| Codex app-server | JSON-RPC/JSONL | rich item/turn events | configurable | Planned |
| A2A streaming | HTTP SSE | incremental status | AgentCard-defined | Bidirectional implemented |
| Durable webhook | Outbox + signed HTTP | event envelope | deployment policy | Implemented |
| Kafka | Transactional Outbox + producer ack | JSON event envelope | broker ACL | Implemented |
| RocketMQ 5.x | Transactional Outbox + gRPC producer | JSON event envelope | broker ACL | Implemented |

The adapter table describes code support, not a claim that every vendor CLI is installed or authenticated in every deployment.

## Quick start

Requirements: Java 17+ and Maven 3.8+.

```bash
git clone git@github.com:WarmHam04/sa-agentlink.git
cd sa-agentlink
mvn test
mvn spring-boot:run
```

The API listens on `http://localhost:8096`.

```bash
# Local demo agents plus detected CLI runtimes
curl http://localhost:8096/api/v1/agents

# Dependency-aware local demo workflow
curl -X POST http://localhost:8096/api/v1/workflows \
  -H 'Content-Type: application/json' \
  --data @examples/workflow.json

# Cross-runtime workflow: Codex + Gemini -> Claude
curl -X POST http://localhost:8096/api/v1/workflows \
  -H 'Content-Type: application/json' \
  --data @examples/cross-runtime-workflow.json
```

Use the returned `contextId` to query the workflow or stream events:

```bash
curl http://localhost:8096/api/v1/workflows/{contextId}
curl -N 'http://localhost:8096/api/v1/contexts/{contextId}/events?after=0'
```

The SSE `id` is the event offset. Reconnect with the last received value in `after` to replay missed events.

### Durable mode

Memory mode is the zero-configuration default. For restart-safe workflows and events, start PostgreSQL and AgentLink together:

```bash
docker compose up --build
```

Add a second coordinator against the same database with the optional cluster profile:

```bash
docker compose --profile cluster up --build
```

Or point a locally started process at PostgreSQL:

```bash
AGENTLINK_PERSISTENCE_MODE=jdbc \
AGENTLINK_JDBC_URL=jdbc:postgresql://localhost:55432/agentlink \
AGENTLINK_JDBC_USERNAME=agentlink \
AGENTLINK_JDBC_PASSWORD=agentlink \
mvn spring-boot:run
```

Flyway owns the schema. Workflow snapshots, the normalized Task index, Registry leases, execution leases, ordered Task events, and Outbox claims are persisted in the same database. Existing snapshots are backfilled into the Task index on startup. Interrupted non-terminal tasks are moved to `FAILED` only after the recovering node obtains the Workflow lease, because replaying an arbitrary external Agent process is not guaranteed to be safe.

For a cluster, start multiple application instances against the same PostgreSQL database and give every instance a stable unique ID:

```bash
AGENTLINK_INSTANCE_ID=agentlink-a SERVER_PORT=8096 ... java -jar agentlink.jar
AGENTLINK_INSTANCE_ID=agentlink-b SERVER_PORT=8097 ... java -jar agentlink.jar
```

Each node renews its registered AgentCards. `local://` and `process://` Agents are routable only by their owner node; HTTP A2A Agents can be selected cluster-wide. A fencing token is checked on every leased Workflow write, so an expired owner cannot overwrite a newer owner.

## Selecting a runtime

Each task declares a portable Skill. Set `metadata.agentId` only when a workflow intentionally requires a particular runtime:

```json
{
  "nodeId": "codex-review",
  "skill": "code-review",
  "instruction": "Review the repository without changing files.",
  "dependsOn": [],
  "metadata": {
    "agentId": "codex-cli",
    "sandbox": "read-only",
    "workingDirectory": ".",
    "maxAttempts": "2"
  }
}
```

Without `agentId`, AgentLink chooses a healthy AgentCard supporting the requested Skill and prefers lower load.

## Registering a remote A2A agent

Discover a standard Agent Card:

```bash
curl -X POST http://localhost:8096/api/v1/agents/discover \
  -H 'Content-Type: application/json' \
  -d '{"cardUrl":"https://agent.example.com/.well-known/agent-card.json"}'
```

Private and loopback Agent endpoints are rejected by default. For an explicitly trusted local development network only:

```bash
AGENTLINK_ALLOW_PRIVATE=true mvn spring-boot:run
```

Set the corresponding property as shown in [application.yml](src/main/resources/application.yml); do not enable it on an Internet-facing deployment without an outbound proxy or network policy.

AgentLink itself is discoverable at `/.well-known/agent-card.json`. It supports blocking/immediate requests at `/a2a/message:send`, SSE status updates at `/a2a/message:stream`, and Task lookup/cancel at `/a2a/tasks/{taskId}` and `/a2a/tasks/{taskId}:cancel`. An external coordinator can select an internal Skill through request metadata without knowing which local or remote runtime will execute it.

For durable outbound integration, set `agentlink.outbox.webhook.url` and optionally `agentlink.outbox.webhook.secret`. Delivered envelopes carry an `Idempotency-Key`; signed deliveries use `X-AgentLink-Signature: sha256=...`. Receivers must deduplicate because the relay intentionally uses at-least-once delivery. The same private-network SSRF policy used for remote agents applies to webhook targets.

Kafka and RocketMQ use the same durable envelope and aggregate ID as the message key. A relay node first claims rows with PostgreSQL `FOR UPDATE SKIP LOCKED`, waits for the broker acknowledgement, and only then marks them published:

```bash
# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
AGENTLINK_OUTBOX_KAFKA_TOPIC=agentlink.events java -jar agentlink.jar

# RocketMQ 5.x gRPC
AGENTLINK_OUTBOX_ROCKETMQ_ENDPOINTS=proxy:8081 \
AGENTLINK_OUTBOX_ROCKETMQ_TOPIC=agentlink-events java -jar agentlink.jar
```

### OAuth2/OIDC

The default `permit-all` mode is for local development. An Internet-facing deployment should enable JWT validation against its OIDC issuer:

```bash
AGENTLINK_SECURITY_MODE=oauth2 \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://id.example.com/realms/agentlink \
java -jar agentlink.jar
```

Tokens use these scopes: `agentlink.execute`, `agentlink.read`, `agentlink.cancel`, `agentlink.agents.read`, `agentlink.agents.write`, and `agentlink.metrics`. Health, info, and the public Agent Card remain unauthenticated.

### OpenTelemetry

The service creates a parent Workflow span and child Task spans, attaches normalized runtime events, and injects W3C `traceparent` into outbound A2A calls. Export with standard OpenTelemetry variables:

```bash
OTEL_TRACES_EXPORTER=otlp \
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
java -jar agentlink.jar
```

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `AGENTLINK_WORKSPACE_ROOT` | process working directory | Maximum filesystem scope for CLI agents |
| `agentlink.runtimes.timeout` | `PT10M` | Per CLI execution timeout |
| `agentlink.runtimes.max-artifact-chars` | `100000` | Maximum stored output size |
| `agentlink.runtimes.bootstrap-cli` | `true` | Auto-register detected CLIs |
| `agentlink.security.allow-private-agent-networks` | `false` | Allow private/loopback remote A2A endpoints |
| `AGENTLINK_PERSISTENCE_MODE` | `memory` | Set to `jdbc` for PostgreSQL snapshots/events/Outbox |
| `AGENTLINK_JDBC_URL` | `jdbc:postgresql://localhost:5432/agentlink` | Durable store JDBC URL |
| `AGENTLINK_INSTANCE_ID` | random UUID | Stable, unique cluster member ID |
| `agentlink.cluster.workflow-lease-ttl` | `PT30S` | Workflow ownership lease duration |
| `agentlink.cluster.agent-registration-ttl` | `PT45S` | Agent Registry registration lifetime |
| `agentlink.outbox.webhook.url` | unset | Enable durable webhook delivery when configured |
| `AGENTLINK_OUTBOX_KAFKA_TOPIC` | unset | Enable Kafka Outbox publisher |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | unset | Kafka bootstrap servers |
| `AGENTLINK_OUTBOX_ROCKETMQ_ENDPOINTS` | unset | RocketMQ 5.x proxy endpoint |
| `AGENTLINK_OUTBOX_ROCKETMQ_TOPIC` | unset | Enable RocketMQ Outbox publisher |
| `AGENTLINK_SECURITY_MODE` | `permit-all` | Set to `oauth2` for JWT/OIDC enforcement |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | unset | Trusted OIDC issuer |
| `OTEL_TRACES_EXPORTER` | `none` | OpenTelemetry trace exporter, for example `otlp` |

## Architecture

```text
io.agentlink.domain          portable collaboration model and invariants
io.agentlink.application     orchestration use cases and infrastructure ports
io.agentlink.infrastructure runtime adapters, registry, workflow store, event bus
io.agentlink.api             REST/SSE endpoints and validation
```

See [docs/architecture.md](docs/architecture.md) for consistency rules and trust boundaries, and [docs/production.md](docs/production.md) for deployment guidance.

## Current limitations

This is a production-oriented early framework, not a complete control plane. PostgreSQL is currently the coordination dependency; there is no multi-region consensus or Redis lease backend. Registry membership is lease-based rather than backed by Kubernetes/Consul discovery. OAuth authorization is scope-based, without tenant/resource ABAC. Kafka and RocketMQ delivery is at-least-once, so consumers must deduplicate by Outbox ID. Automatic retries cannot prove that an arbitrary external Agent action is idempotent. A2A push-notification registration, durable streaming cursors across services, administrative UI, quotas, admission control, and soak/chaos benchmarks remain future work.

## Contributing

Issues and focused pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before contributing.

## License

[MIT](LICENSE)
