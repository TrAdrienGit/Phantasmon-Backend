-- Live Ghost battles (Phase 9): the host client runs the battle engine. The backend remembers who hosted each
-- battle so successive battles between the same two players alternate the host (CAD Partie 2 §9.2 guardrail).
-- Nullable: battles created before V8 (REST POST /battles) have no host.
ALTER TABLE battle_sessions ADD COLUMN host_uuid UUID REFERENCES players(uuid) ON DELETE RESTRICT;

CREATE INDEX idx_battle_host ON battle_sessions(host_uuid);
