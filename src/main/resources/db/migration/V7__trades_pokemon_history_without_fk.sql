-- trades.offered_pokemon / requested_pokemon become historical snapshots of Pokémon
-- uuids, with no SQL foreign key anymore (Adrien, 2026-10-02) — same reasoning as
-- battle_sessions.team_a/team_b (PHANTASMON_DB_SCHEMA.md §6).
--
-- The V3 `ON DELETE RESTRICT` FKs blocked deleting *any* Pokémon that had ever been
-- traded, even long after the trade was COMPLETED or CANCELLED (DELETE /pokemon/{uuid}
-- failed with a raw constraint violation). The only case that deserves blocking — a
-- Pokémon still engaged in a PENDING trade — is now enforced by PokemonService.delete
-- (ERROR_POKEMON_IN_PENDING_TRADE) instead of the database.
--
-- V3's FKs were unnamed, so Postgres auto-generated their names — found dynamically
-- here (same approach as V6) rather than assuming the `<table>_<column>_fkey` convention.
-- The FKs to players(uuid) are untouched.
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'trades'::regclass
          AND contype = 'f'
          AND confrelid = 'pokemon'::regclass
    LOOP
        EXECUTE format('ALTER TABLE trades DROP CONSTRAINT %I', fk.conname);
    END LOOP;
END $$;

CREATE INDEX idx_trades_offered_pokemon ON trades(offered_pokemon);
CREATE INDEX idx_trades_requested_pokemon ON trades(requested_pokemon);
