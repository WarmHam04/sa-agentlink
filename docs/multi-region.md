# Multi-region ownership and failover

SA-AgentLink implements a single-writer-per-tenant regional model. It does not claim multi-primary consensus.

## Invariants

1. Every tenant policy has one `home_region` and one monotonically increasing `routing_epoch`.
2. New Workflow admission succeeds only in the home region.
3. A persisted Workflow carries the routing epoch under which it was admitted.
4. Workflow lease acquisition and renewal require the policy region and epoch to match.
5. Snapshot updates compare the in-memory epoch with the durable Workflow row.
6. Failover is an administrator compare-and-set operation; concurrent operators cannot both apply the same expected epoch.

These checks fence a paused old-region process even if it resumes after the routing decision changed.

## Planned topology

The current tested topology uses multiple application regions against one writable PostgreSQL authority. For geographic disaster recovery, PostgreSQL replication and promotion remain infrastructure responsibilities. AgentLink must connect to the promoted writer before changing tenant ownership.

Do not point two independent writable databases at the same tenant. Async logical replication cannot resolve concurrent Workflow state transitions or lease fencing safely.

## Controlled failover

1. Stop or drain admission in the current home region.
2. Confirm the target PostgreSQL writer contains all committed migrations and tenant data.
3. Record the current tenant policy and expected routing epoch.
4. Call `POST /api/v1/admin/tenant-policy:failover` in administrator context:

```json
{
  "homeRegion": "region-b",
  "expectedRoutingEpoch": 7
}
```

5. Route tenant traffic to the new region.
6. Observe old-region lease-renewal failures and new-region recovery.
7. Inspect interrupted Tasks. Recovery marks them failed rather than replaying potentially non-idempotent Agent actions.

The failover transaction increments the epoch, updates durable Workflow epochs, and deletes existing Workflow leases. A stale operator receives `409 Conflict` when the expected epoch no longer matches.

## Recovery objectives

RPO is bounded by the database replication mode chosen outside AgentLink. RTO includes database promotion, connection recovery, the failover transaction, traffic routing, and Task reconciliation. Do not publish an RPO/RTO until this complete path has been measured under the deployment's actual database and network topology.

## Fault tests

Use `compose.chaos.yaml` and `tests/chaos/postgres-toxic.sh` to inject latency or a TCP partition between AgentLink and PostgreSQL. Minimum acceptance checks are:

- no stale snapshot commits after an epoch change;
- at most one live Workflow lease owner;
- expired Outbox claims are recoverable;
- DLQ records remain tenant-isolated;
- the API returns bounded errors rather than hanging indefinitely;
- recovery does not automatically replay external Agent side effects.
