-- Rewards (TODO-37, 2026-11-11): the looks an admin gave a player (a filter, an aura, the right to resize), for all
-- their Pokémon or one species only. A Pokémon's own look stays in its data ("look"); these say what a player may
-- put there.
CREATE TABLE player_rewards (
    uuid        UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    kind        VARCHAR(16) NOT NULL,
    value       VARCHAR(32) NOT NULL,
    species     VARCHAR(64),
    note        VARCHAR(100),
    granted_by  UUID,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX player_rewards_player ON player_rewards (player_uuid);
CREATE UNIQUE INDEX player_rewards_unique ON player_rewards (player_uuid, kind, value, COALESCE(species, ''));
