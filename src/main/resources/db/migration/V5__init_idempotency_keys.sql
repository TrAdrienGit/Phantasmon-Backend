CREATE TABLE idempotency_keys (
    request_uuid        UUID PRIMARY KEY,
    player_uuid          UUID NOT NULL,
    endpoint              VARCHAR(64) NOT NULL,
    response_snapshot     JSONB,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_player_endpoint ON idempotency_keys(player_uuid, endpoint);
