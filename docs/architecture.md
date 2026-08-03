# Architecture

## Core rule

The orchestration core depends on portable Agent-to-Agent semantics, not on a provider SDK or CLI output schema. Provider differences stop at the Runtime Adapter boundary.

```text
REST / SSE / inbound A2A
            |
   WorkflowOrchestrator -------- AgentLinkTelemetry
      |       |       |                 |
      |       |       |                 `-- OTLP / W3C trace context
      |       |       `-- AgentInvoker -- RuntimeAdapter[]
      |       |                            |-- Codex / Gemini / Claude / agy CLI
      |       |                            `-- remote A2A blocking or SSE
      |       |
      |       `-- AgentDirectory -------- PostgreSQL Registry + Skill index
      |
      `-- WorkflowStore ----------------- snapshot + normalized Task index
              |                           optimistic version + fenced lease
              `-- TaskEventBus ---------- ordered SSE replay + Outbox
                                                   |
                                      Webhook / Kafka / RocketMQ
```

## Portable model

- **AgentCard** identifies an opaque Agent, transport endpoint, streaming capability, health, load, and Skills.
- **Workflow** groups related work under a Context and owns an optimistic version.
- **TaskNodeSpec** defines Skill, instruction, dependencies, and adapter metadata.
- **AgentTask** enforces state transitions and owns one resulting Artifact.
- **Artifact** is the data dependency passed between Task nodes.
- **TaskEvent** is the durable externally streamed status record.
- **RuntimeEvent** normalizes provider progress without leaking provider objects into orchestration.

## DAG execution

The orchestrator rejects missing dependencies, self-dependencies, duplicate nodes, and cycles before persisting work. `CompletableFuture.allOf` forms dependency barriers; independent branches run concurrently. A downstream Task starts only after all dependency Artifacts are available. Failed dependencies reject downstream Tasks without invoking an Agent.

## Cluster ownership and persistence

PostgreSQL stores two complementary Workflow representations:

- the JSON snapshot reconstructs the aggregate and carries a monotonic optimistic version;
- `agentlink_task` is the normalized query index for Task ID, Context, state, node, Skill, Agent, and timestamps.

A write compares the expected snapshot version. Execution additionally requires a live row in `agentlink_workflow_lease` whose owner and fencing token match. Lease acquisition increments the fencing token; renewal never changes it. This prevents a paused or partitioned coordinator from overwriting a newer owner after its lease expires.

Terminal completion releases the lease. Startup recovery must acquire a lease before converting interrupted Tasks to `FAILED`. The system deliberately does not blindly replay external Agent calls because their side effects may not be idempotent.

Every durable resource carries a tenant ID. Workflow/Task lookup and event replay require the caller tenant, and Outbox/DLQ administration filters by the same boundary. Agent registrations are tenant-private unless registered as `_system`, which is reserved for coordinator-owned shared runtimes.

## Tenant admission and regional fencing

The JWT tenant claim is converted into an immutable request identity after signature, issuer, time, and scope validation. An administrator can explicitly override it with `X-AgentLink-Tenant`; ordinary callers cannot. Resource mismatches resolve as not found to avoid identifier enumeration.

PostgreSQL stores tenant limits and home-region ownership. Request admission uses atomic fixed-window counters. Workflow admission locks the tenant policy row, consumes the daily quota, checks active reservations, and inserts a renewable Context reservation. The policy-row lock serializes concurrent admissions for the same tenant.

A controlled region failover is a compare-and-set operation on `routing_epoch`. In one transaction it changes `home_region`, increments the epoch, advances persisted Workflow epochs, and revokes old Workflow leases. An old process retains the former epoch in memory, so its next lease renewal or snapshot compare-and-set fails.

## Cluster Agent Registry

In JDBC mode AgentCards and Skills are normalized in PostgreSQL. Registration records contain owner instance, revision, and lease expiry. Each node renews its owned records and periodically removes expired membership.

Transport determines reachability:

- `local://` and `process://` records are visible for administration but selectable only by their owner node;
- `http://` and `https://` A2A records are selectable by every node;
- routing filters by live lease, health, requested Skill, then lowest declared load and stable Agent ID.

This avoids sending a Task to a CLI process installed only on another coordinator while still allowing cluster-wide remote A2A routing.

## Adapter boundary and A2A

CLI adapters construct fixed argument arrays and translate provider streams into RuntimeEvents:

- Codex: JSONL `thread.*`, `turn.*`, and `item.*` events.
- Gemini CLI: stream-json `init`, `message`, `tool_use`, `tool_result`, `error`, and `result` events.
- Claude Code: stream-json assistant/result events.
- Antigravity `agy`: final text until a structured streaming mode is available.

For a remote Agent, the discovered AgentCard declares whether streaming is supported. AgentLink chooses `message:stream` for streaming peers, parses SSE event boundaries including multiline `data:` fields, normalizes status updates, and retains the remote Task ID for cancellation. Non-streaming peers use `message:send`. Both paths inject W3C trace context.

## Durable event delivery

Task events and Outbox rows commit in the same database transaction. Relay nodes atomically claim batches. PostgreSQL uses `FOR UPDATE SKIP LOCKED`; portable test mode uses conditional updates. Claims have an owner and expiry so another node can recover after a relay crash.

Webhook, Kafka, and RocketMQ publishers share one JSON envelope. Kafka keys records by aggregate ID and includes Outbox/event headers. RocketMQ uses the official 5.x gRPC producer. The row is marked published only after every configured publisher acknowledges it. Delivery is intentionally at-least-once, so receivers deduplicate by Outbox ID.

Failures use bounded exponential backoff. After the configured maximum attempt, the row becomes a tenant-scoped dead letter and is excluded from relay claims. An administrator can inspect and explicitly replay it; replay clears failure state and resets the attempt counter without changing the stable Outbox ID.

## Security boundary

Local development defaults to `permit-all`. OAuth2 mode configures a stateless JWT Resource Server and separates execute, read, cancel, Agent Registry, metrics, and administrator scopes. Identity verification comes from the configured OIDC issuer; AgentLink does not issue tokens. Tenant ABAC is applied in addition to scopes.

Independent defenses remain necessary:

- ProcessBuilder receives a fixed argument list; no shell interpolation boundary exists.
- Adapter permission modes are allow-listed.
- CLI working directories must remain under one configured workspace root.
- Remote URLs reject credentials and private/local address ranges by default.
- Output is truncated before storage to bound memory and database growth.
- Broker and database credentials belong in the deployment secret store.

## Observability

Each Workflow creates an OpenTelemetry parent span. Task spans contain Context, Task, node, Skill, selected Agent, runtime events, terminal state, and recorded failures. Lease loss is a Workflow event and error status. The standard OpenTelemetry Spring Boot instrumentation covers inbound HTTP and database calls, while the A2A adapter propagates W3C context outbound.

Prometheus metrics and health probes complement traces. Production alerting should include failure rates, lease loss, Registry expiry, Outbox backlog/attempt counts, JVM saturation, and PostgreSQL pressure.

## Deliberate limitations

The design is a horizontally scalable production control-plane baseline, not a finished enterprise platform. PostgreSQL is still the coordination authority. Regional ownership is fenced single-writer routing, not multi-primary consensus. There is no durable A2A push-notification subscription, management UI, or published long-duration soak/chaos SLO evidence yet.
