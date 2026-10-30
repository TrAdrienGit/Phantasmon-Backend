-- Double elimination brackets (plan-tournois.md, T4): the wave in which an admin opens a match (Swiss: unused, 0).
ALTER TABLE tournament_matches ADD COLUMN wave SMALLINT NOT NULL DEFAULT 0;
