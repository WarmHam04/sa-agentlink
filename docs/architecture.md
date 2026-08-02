# Architecture

## Core rule

The orchestration core depends on portable Agent-to-Agent semantics, not on a provider SDK or CLI output schema.

```text
REST/SSE API
    |
WorkflowOrchestrator
    |-- AgentDirectory
    |-- WorkflowStore
    |-- TaskEventBus
    `-- AgentInvoker
            `-- AgentRuntimeAdapter[]
                  |-- local
                  |-- codex CLI
                  |-- gemini CLI
                  |-- claude CLI
                  |-- agy CLI
                  `-- remote A2A HTTP
```

## Portable model

- **AgentCard** identifies an opaque Agent and its Skills.
- **Workflow** groups related work under a Context.
- **TaskNodeSpec** defines Skill, instruction, dependencies, and adapter metadata.
- **AgentTask** enforces state transitions and owns one resulting Artifact.
- **Artifact** is the only data dependency passed between Task nodes.
- **TaskEvent** is the externally streamed status record.
- **RuntimeEvent** normalizes provider progress without exposing provider objects upstream.

## DAG execution

The orchestrator validates all node IDs and dependencies before execution. It rejects missing dependencies, self-dependencies, duplicates, and cycles. `CompletableFuture.allOf` forms dependency barriers; independent branches execute concurrently. A downstream task starts only after all dependency Artifacts are available.

## Adapter boundary

CLI adapters translate provider event streams into RuntimeEvents:

- Codex: JSONL `thread.*`, `turn.*`, and `item.*` events.
- Gemini CLI: stream-json `init`, `message`, `tool_use`, `tool_result`, `error`, and `result` events.
- Claude Code: stream-json assistant/result events.
- Antigravity `agy`: final text until a structured streaming mode becomes public.

Remote agents use A2A HTTP `message:send` and Agent Card discovery. AgentLink's inbound surface also provides SSE status streaming plus Task lookup and cancellation; outbound remote invocation currently uses blocking send.

## Durability and delivery

`memory` mode keeps the development path small. `jdbc` mode uses PostgreSQL and Flyway for Workflow snapshots, ordered TaskEvents, and Outbox messages. Aggregate snapshots are written after every state transition; event offsets come from the database and can be replayed after an SSE reconnect.

On startup, non-terminal snapshots are failed explicitly. This is a safety decision: an external CLI or remote Agent may have produced irreversible side effects before the coordinator stopped, so blind replay would violate at-most-once intent.

The Outbox relay is at-least-once. A successful publisher batch sets `published_at`; a crash between delivery and marking can repeat an envelope, so publishers and receivers use the Outbox ID as an idempotency key. The included publisher supports optional HMAC-signed HTTP webhooks, while the `OutboxPublisher` port is the extension point for RocketMQ or Kafka.

Cancellation moves all non-terminal Tasks in a Workflow to `CANCELED`, cancels their futures, and invokes the selected Runtime Adapter. CLI adapters terminate the tracked child process and force termination after a grace period.

## Security decisions

- ProcessBuilder receives a fixed argument list; no `/bin/sh -c` boundary exists.
- Adapter permission modes are allow-listed.
- CLI working directories must remain under one configured workspace root.
- Remote URLs reject credentials and private/local address ranges by default.
- Output is truncated before storage to avoid unbounded memory growth.

## Production roadmap

The durable baseline is implemented, but larger deployments still need:

- normalized Task indexes and optimistic snapshot versions;
- broker-specific Outbox publishers for RocketMQ or Kafka;
- Redis hot Task snapshots and distributed idempotency keys;
- OpenTelemetry spans keyed by Context, Task, Agent, and Artifact;
- OAuth/OIDC and per-Agent authorization policies.
