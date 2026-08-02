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
- Runtime Adapter SPI with safe argument lists—no shell command interpolation.
- Safe defaults: Codex read-only sandbox, Gemini/Claude/agy plan mode, workspace-root confinement, process timeout, and bounded Artifact size.
- Bounded retries with exponential backoff (`maxAttempts`, 1–5).
- Remote A2A HTTP invocation, Agent Card discovery, and an inbound AgentLink A2A endpoint.
- SSRF protection for remote Agent endpoints; private networks are denied by default.

## Runtime support

| Runtime | Transport | Progress | Default permission | Status |
|---|---|---|---|---|
| OpenAI Codex CLI | `codex exec --json` | JSONL | `read-only` | Implemented |
| Google Gemini CLI | `--output-format stream-json` | JSONL | `plan` | Implemented |
| Anthropic Claude Code | `--output-format stream-json` | JSONL | `plan` | Implemented |
| Google Antigravity `agy` | `--print` | final text | `plan` | Implemented, limited streaming |
| Remote A2A agent | HTTP `message:send` | final Task/Message | AgentCard-defined | Inbound + outbound implemented |
| Codex app-server | JSON-RPC/JSONL | rich item/turn events | configurable | Planned |
| A2A streaming/push | SSE/Webhook | incremental artifacts | AgentCard-defined | Planned |

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

AgentLink itself is discoverable at `/.well-known/agent-card.json` and accepts blocking or immediate A2A requests at `/a2a/message:send`. An external A2A coordinator can select an internal Skill through request metadata without knowing which local or remote runtime will execute it.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `AGENTLINK_WORKSPACE_ROOT` | process working directory | Maximum filesystem scope for CLI agents |
| `agentlink.runtimes.timeout` | `PT10M` | Per CLI execution timeout |
| `agentlink.runtimes.max-artifact-chars` | `100000` | Maximum stored output size |
| `agentlink.runtimes.bootstrap-cli` | `true` | Auto-register detected CLIs |
| `agentlink.security.allow-private-agent-networks` | `false` | Allow private/loopback remote A2A endpoints |

## Architecture

```text
io.agentlink.domain          portable collaboration model and invariants
io.agentlink.application     orchestration use cases and infrastructure ports
io.agentlink.infrastructure runtime adapters, registry, workflow store, event bus
io.agentlink.api             REST/SSE endpoints and validation
```

See [docs/architecture.md](docs/architecture.md) for design decisions and trust boundaries.

## Current limitations

The default WorkflowStore and TaskEventBus are in-memory. Restart recovery, PostgreSQL/Redis persistence, RocketMQ/Kafka delivery, cancellation propagation, A2A streaming, webhook notifications, authentication, and OpenTelemetry are not yet implemented. They remain explicit roadmap items rather than implied production features.

## Contributing

Issues and focused pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before contributing.

## License

[MIT](LICENSE)
