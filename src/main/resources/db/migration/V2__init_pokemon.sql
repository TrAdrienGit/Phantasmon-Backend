CREATE TABLE pokemon (
    uuid                    UUID PRIMARY KEY,
    owner_uuid              UUID NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    species                 VARCHAR(64) NOT NULL,
    form                    VARCHAR(64),
    level                   SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 100),
    nature                  VARCHAR(32) NOT NULL,
    ability                 VARCHAR(64) NOT NULL,
    is_shiny                BOOLEAN NOT NULL DEFAULT FALSE,
    box_id                  SMALLINT CHECK (box_id BETWEEN 1 AND 16),
    box_slot                SMALLINT CHECK (box_slot BETWEEN 1 AND 36),
    team_slot               SMALLINT CHECK (team_slot BETWEEN 1 AND 6),
    cobblemon_data_version  VARCHAR(32) NOT NULL,
    data                    JSONB NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_pokemon_box_coherent CHECK (
        (box_id IS NULL AND box_slot IS NULL) OR (box_id IS NOT NULL AND box_slot IS NOT NULL)
    )
);

CREATE INDEX idx_pokemon_owner ON pokemon(owner_uuid);
CREATE INDEX idx_pokemon_data_gin ON pokemon USING GIN (data);

CREATE UNIQUE INDEX uq_pokemon_pc_slot
    ON pokemon(owner_uuid, box_id, box_slot)
    WHERE box_id IS NOT NULL AND box_slot IS NOT NULL;

CREATE UNIQUE INDEX uq_pokemon_team_slot
    ON pokemon(owner_uuid, team_slot)
    WHERE team_slot IS NOT NULL;
