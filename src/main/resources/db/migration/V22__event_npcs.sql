-- Phantasmon Events: events an admin opens and closes, and their trainer NPCs (Adrien 2026-11-02). An NPC stands at a
-- place of one server and dimension; players can see and challenge it only while its event is open. Its team is a
-- copy of Pokémon (the same JSON as a battle's teams), played by the AI it is given.
CREATE TABLE event_groups (
    name       VARCHAR(32) PRIMARY KEY,
    open       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE event_npcs (
    uuid               UUID PRIMARY KEY,
    event_name         VARCHAR(32)  NOT NULL REFERENCES event_groups(name) ON DELETE CASCADE,
    name               VARCHAR(32)  NOT NULL,
    skin               VARCHAR(16),
    server_fingerprint VARCHAR(128) NOT NULL,
    dimension          VARCHAR(128) NOT NULL,
    x                  DOUBLE PRECISION NOT NULL,
    y                  DOUBLE PRECISION NOT NULL,
    z                  DOUBLE PRECISION NOT NULL,
    yaw                REAL         NOT NULL DEFAULT 0,
    team               JSONB        NOT NULL DEFAULT '[]',
    ai                 VARCHAR(16)  NOT NULL DEFAULT 'normal',
    format_id          VARCHAR(64)  NOT NULL DEFAULT 'free',
    created_by         UUID         NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX event_npcs_place ON event_npcs (server_fingerprint, dimension);
