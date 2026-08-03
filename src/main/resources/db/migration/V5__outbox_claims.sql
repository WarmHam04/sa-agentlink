ALTER TABLE agentlink_outbox ADD COLUMN claimed_by VARCHAR(128);
ALTER TABLE agentlink_outbox ADD COLUMN claimed_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE agentlink_outbox ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agentlink_outbox ADD COLUMN last_error TEXT;

CREATE INDEX idx_agentlink_outbox_claim
    ON agentlink_outbox (published_at, claimed_until, outbox_id);
