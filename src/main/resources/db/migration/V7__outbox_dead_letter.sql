ALTER TABLE agentlink_outbox ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE agentlink_outbox ADD COLUMN dead_lettered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE agentlink_outbox ADD COLUMN dead_letter_reason TEXT;

UPDATE agentlink_outbox SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;
ALTER TABLE agentlink_outbox ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX idx_agentlink_outbox_retry
    ON agentlink_outbox (dead_lettered_at, published_at, next_attempt_at, outbox_id);
CREATE INDEX idx_agentlink_outbox_dlq_tenant
    ON agentlink_outbox (tenant_id, dead_lettered_at, outbox_id);
