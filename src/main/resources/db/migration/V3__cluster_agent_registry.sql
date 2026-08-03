CREATE TABLE agentlink_agent_registry (
    agent_id          VARCHAR(192) PRIMARY KEY,
    name              VARCHAR(256) NOT NULL,
    endpoint          TEXT NOT NULL,
    protocol_version  VARCHAR(64) NOT NULL,
    healthy           BOOLEAN NOT NULL,
    load_value        INTEGER NOT NULL,
    owner_instance_id VARCHAR(128) NOT NULL,
    lease_until       TIMESTAMP WITH TIME ZONE NOT NULL,
    revision          BIGINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE agentlink_agent_skill (
    agent_id     VARCHAR(192) NOT NULL REFERENCES agentlink_agent_registry(agent_id) ON DELETE CASCADE,
    skill_name   VARCHAR(128) NOT NULL,
    description  TEXT NOT NULL,
    PRIMARY KEY (agent_id, skill_name)
);

CREATE INDEX idx_agentlink_agent_lease ON agentlink_agent_registry (lease_until, healthy);
CREATE INDEX idx_agentlink_agent_skill_name ON agentlink_agent_skill (skill_name, agent_id);
