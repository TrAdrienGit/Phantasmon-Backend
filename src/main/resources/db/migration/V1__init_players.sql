CREATE TABLE players (
    uuid            UUID PRIMARY KEY,
    last_username   VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ
);
