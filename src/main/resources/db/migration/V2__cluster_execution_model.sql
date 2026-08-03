ALTER TABLE agentlink_workflow
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE agentlink_task (
    task_id        VARCHAR(128) PRIMARY KEY,
    context_id     VARCHAR(128) NOT NULL REFERENCES agentlink_workflow(context_id) ON DELETE CASCADE,
    node_id        VARCHAR(128) NOT NULL,
    skill          VARCHAR(128) NOT NULL,
    state          VARCHAR(32) NOT NULL,
    agent_id       VARCHAR(128),
    artifact_id    VARCHAR(128),
    error          TEXT,
    spec_json      TEXT NOT NULL,
    artifact_json  TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_agentlink_task_context_node UNIQUE (context_id, node_id)
);

CREATE INDEX idx_agentlink_task_context ON agentlink_task (context_id);
CREATE INDEX idx_agentlink_task_state ON agentlink_task (state, updated_at);
CREATE INDEX idx_agentlink_task_agent ON agentlink_task (agent_id, state);

CREATE TABLE agentlink_workflow_lease (
    context_id     VARCHAR(128) PRIMARY KEY REFERENCES agentlink_workflow(context_id) ON DELETE CASCADE,
    owner_id       VARCHAR(128) NOT NULL,
    fencing_token  BIGINT NOT NULL,
    lease_until    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agentlink_lease_expiry ON agentlink_workflow_lease (lease_until);

-- Backfill the normalized index from existing snapshots is application-specific. Existing
-- snapshots remain readable and are indexed on their next write.
