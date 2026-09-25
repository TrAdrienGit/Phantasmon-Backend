CREATE TABLE battle_sessions (
    uuid            UUID PRIMARY KEY,
    player_a        UUID NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    player_b        UUID NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    team_a          JSONB NOT NULL,
    team_b          JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'ACTIVE', 'FINISHED', 'ABORTED')),
    result          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,

    CONSTRAINT chk_battle_not_self CHECK (player_a <> player_b)
);

CREATE INDEX idx_battle_player_a ON battle_sessions(player_a);
CREATE INDEX idx_battle_player_b ON battle_sessions(player_b);
