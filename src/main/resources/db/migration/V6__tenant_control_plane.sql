ALTER TABLE agentlink_workflow ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '_default';
ALTER TABLE agentlink_workflow ADD COLUMN routing_epoch BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agentlink_task ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '_default';
ALTER TABLE agentlink_task_event ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '_default';
ALTER TABLE agentlink_outbox ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '_default';
ALTER TABLE agentlink_agent_registry ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '_system';

CREATE INDEX idx_agentlink_workflow_tenant ON agentlink_workflow (tenant_id, created_at);
CREATE INDEX idx_agentlink_task_tenant_state ON agentlink_task (tenant_id, state, updated_at);
CREATE INDEX idx_agentlink_event_tenant_context ON agentlink_task_event (tenant_id, context_id, event_offset);
CREATE INDEX idx_agentlink_outbox_tenant_delivery ON agentlink_outbox (tenant_id, published_at, outbox_id);
CREATE INDEX idx_agentlink_agent_tenant_lease ON agentlink_agent_registry (tenant_id, lease_until, healthy);

CREATE TABLE agentlink_tenant_policy (
    tenant_id                 VARCHAR(64) PRIMARY KEY,
    enabled                   BOOLEAN NOT NULL DEFAULT TRUE,
    home_region               VARCHAR(64) NOT NULL,
    routing_epoch             BIGINT NOT NULL DEFAULT 1,
    requests_per_minute       INTEGER NOT NULL DEFAULT 600,
    workflows_per_day         INTEGER NOT NULL DEFAULT 10000,
    max_active_workflows      INTEGER NOT NULL DEFAULT 100,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE agentlink_tenant_rate_bucket (
    tenant_id     VARCHAR(64) NOT NULL,
    operation     VARCHAR(64) NOT NULL,
    bucket_start  TIMESTAMP WITH TIME ZONE NOT NULL,
    request_count INTEGER NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, operation, bucket_start)
);

CREATE INDEX idx_agentlink_rate_bucket_expiry
    ON agentlink_tenant_rate_bucket (bucket_start);

CREATE TABLE agentlink_tenant_workflow_reservation (
    context_id  VARCHAR(128) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL REFERENCES agentlink_tenant_policy(tenant_id) ON DELETE CASCADE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agentlink_reservation_tenant_expiry
    ON agentlink_tenant_workflow_reservation (tenant_id, expires_at);
