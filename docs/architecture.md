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

Remote agents use the A2A HTTP `message:send` operation and Agent Card discovery. Standard A2A streaming, polling, push notification, and authentication coverage is planned.

## Security decisions

- ProcessBuilder receives a fixed argument list; no `/bin/sh -c` boundary exists.
- Adapter permission modes are allow-listed.
- CLI working directories must remain under one configured workspace root.
- Remote URLs reject credentials and private/local address ranges by default.
- Output is truncated before storage to avoid unbounded memory growth.

## Production adapter roadmap

The current in-memory ports are useful for local development and tests. Production profiles should provide:

- PostgreSQL workflow/task/artifact persistence with optimistic versions;
- an Outbox relay to RocketMQ or Kafka;
- Redis hot Task snapshots and distributed idempotency keys;
- durable event offsets and replay;
- OpenTelemetry spans keyed by Context, Task, Agent, and Artifact;
- OAuth/OIDC and per-Agent authorization policies.
