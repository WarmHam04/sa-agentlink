# Production deployment

SA-AgentLink can run as a horizontally scaled coordination service when every instance shares PostgreSQL and has a stable, unique `AGENTLINK_INSTANCE_ID`. This document describes the production boundary of the current early framework.

## Required baseline

- PostgreSQL 15+ with backups, connection limits, TLS, and Flyway migration ownership.
- Two or more AgentLink instances behind an HTTP load balancer.
- A stable unique instance ID per replica. A Kubernetes pod UID or StatefulSet ordinal is suitable.
- OAuth2 mode with a trusted OIDC issuer for every non-development environment.
- OpenTelemetry export to a collector and Prometheus scraping of `/actuator/prometheus`.
- At least one Outbox publisher when downstream systems consume lifecycle events.

Do not share one instance ID between live replicas. Lease and Registry ownership use this value to distinguish coordinators.

## Consistency model

Workflow snapshots use optimistic compare-and-set updates. A coordinator must also hold a PostgreSQL execution lease; every leased write checks both the owner and monotonically increasing fencing token. A stale coordinator therefore fails its write after another node takes ownership.

The normalized `agentlink_task` table is the query index. The Workflow JSON snapshot remains the aggregate source used for reconstruction. Flyway creates the index and a startup runner backfills legacy snapshots.

Agent registrations are soft state. Nodes heartbeat their owned cards, and expired cards are pruned. Process and local endpoints never route to another node; HTTP(S) A2A endpoints are cluster-routable.

Outbox delivery is at-least-once. PostgreSQL claims use `FOR UPDATE SKIP LOCKED`; each record includes its Outbox ID as an idempotency key or broker header. A consumer must persist that ID before applying non-idempotent effects.

## OAuth2 scopes

| Scope | Access |
|---|---|
| `agentlink.execute` | Submit REST or A2A work |
| `agentlink.read` | Read Workflows, Tasks, and SSE events |
| `agentlink.cancel` | Cancel REST or A2A Tasks |
| `agentlink.agents.read` | List/read registered Agents |
| `agentlink.agents.write` | Register, discover, heartbeat, or remove Agents |
| `agentlink.metrics` | Read Prometheus metrics |

The issuer's JWT must expose scopes using Spring Security's standard `scope` or `scp` claims. Network policy should separately restrict PostgreSQL, broker, actuator, and outbound Agent traffic.

## Broker delivery

Configure only the publishers required by the deployment. If more than one is enabled, an Outbox row is marked published only after every configured publisher acknowledges it. A partial success can be delivered again after retry, which is why deduplication remains mandatory.

Kafka configuration uses Spring Boot's `spring.kafka.*` properties and `agentlink.outbox.kafka.topic`. RocketMQ uses the official 5.x gRPC client with `agentlink.outbox.rocketmq.*`. Secrets should come from the platform secret store rather than source-controlled configuration.

## Observability

Workflow and Task spans include Context ID, Task ID, node, Skill, selected Agent, state, runtime events, failures, and lease-loss events. Outbound A2A requests carry W3C trace context. Set the standard `OTEL_EXPORTER_OTLP_*` variables to use an OpenTelemetry Collector; keep exporters disabled in unit tests and local runs that do not have a collector.

Alert on failed/rejected Task rates, lease-loss events, Outbox backlog age, repeated publish attempts, expired Registry membership, JVM saturation, and PostgreSQL connection pressure.

## Shutdown and recovery

Use graceful HTTP shutdown and a termination grace period longer than the longest expected database write. A killed coordinator stops renewing its Workflow lease; another node can recover only after expiry. Startup recovery acquires the lease and marks interrupted work failed instead of blindly replaying a potentially non-idempotent external action.

## Known gaps

- No multi-region consensus or active-active database strategy.
- Scope authorization is not tenant-aware ABAC/RBAC.
- No durable A2A push-notification subscription service.
- No built-in broker provisioning, schema registry, or dead-letter administration.
- No chaos/soak SLO evidence yet.
- No management UI, quotas, or admission controller.

These boundaries make the project suitable for controlled production pilots and further framework development, not a claim of turnkey enterprise platform maturity.
