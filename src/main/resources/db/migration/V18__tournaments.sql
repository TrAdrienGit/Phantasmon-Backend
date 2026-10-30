-- Tournaments (Phantasmon Events, plan-tournois.md, Adrien 2026-10-29): weekly Swiss tournaments and double
-- elimination brackets, played in Ghost battles, run by Phantasmon's admins.
CREATE TABLE tournaments (
    uuid                   UUID PRIMARY KEY,
    name                   VARCHAR(32) NOT NULL,
    kind                   VARCHAR(8) NOT NULL CHECK (kind IN ('SWISS', 'BRACKET')),
    best_of                SMALLINT NOT NULL CHECK (best_of IN (1, 3, 5)),
    format_id              VARCHAR(64) NOT NULL,
    status                 VARCHAR(12) NOT NULL DEFAULT 'REGISTRATION'
        CHECK (status IN ('REGISTRATION', 'CHECK_IN', 'RUNNING', 'FINISHED', 'CANCELLED')),
    starts_at              TIMESTAMPTZ,
    registration_opens_at  TIMESTAMPTZ,
    registration_closes_at TIMESTAMPTZ,
    planned_rounds         SMALLINT,
    current_round          SMALLINT NOT NULL DEFAULT 0,
    created_by             UUID REFERENCES players(uuid) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A name names one tournament among those not over yet (commands use it).
CREATE UNIQUE INDEX uq_tournament_open_name ON tournaments(lower(name)) WHERE status NOT IN ('FINISHED', 'CANCELLED');

CREATE TABLE tournament_players (
    tournament_uuid UUID NOT NULL REFERENCES tournaments(uuid) ON DELETE CASCADE,
    player_uuid     UUID NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    username        VARCHAR(16) NOT NULL,
    checked_in      BOOLEAN NOT NULL DEFAULT false,
    dropped         BOOLEAN NOT NULL DEFAULT false,
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_uuid, player_uuid)
);

-- A match of a round (Swiss) or of the bracket; player2 null: a bye. Games won by each side (best of 1, 3 or 5).
CREATE TABLE tournament_matches (
    uuid            UUID PRIMARY KEY,
    tournament_uuid UUID NOT NULL REFERENCES tournaments(uuid) ON DELETE CASCADE,
    round           SMALLINT NOT NULL,
    bracket         VARCHAR(8) NOT NULL CHECK (bracket IN ('SWISS', 'WINNERS', 'LOSERS', 'FINAL', 'RESET')),
    position        SMALLINT NOT NULL,
    player1_uuid    UUID,
    player2_uuid    UUID,
    wins1           SMALLINT NOT NULL DEFAULT 0,
    wins2           SMALLINT NOT NULL DEFAULT 0,
    winner_uuid     UUID,
    status          VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PLAYING', 'DONE')),
    forfeit         BOOLEAN NOT NULL DEFAULT false,
    edited_by       UUID,
    UNIQUE (tournament_uuid, bracket, round, position)
);

CREATE INDEX idx_tournament_matches_tournament ON tournament_matches(tournament_uuid);
