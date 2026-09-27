-- Ghost PC box resized from 6x6 (36 slots) to 6x5 (30 slots) per box (Adrien, 2026-09-27).
-- Total PC capacity: 16 boxes x 30 slots = 480 (was 576).
--
-- The original CHECK on box_slot (V2) was unnamed, so Postgres auto-generated
-- its name — found dynamically here rather than guessed, so this migration
-- doesn't depend on Postgres's default-naming convention holding.
DO $$
DECLARE
    existing_constraint text;
BEGIN
    SELECT conname INTO existing_constraint
    FROM pg_constraint
    WHERE conrelid = 'pokemon'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%box_slot%';

    IF existing_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE pokemon DROP CONSTRAINT %I', existing_constraint);
    END IF;
END $$;

ALTER TABLE pokemon ADD CONSTRAINT chk_pokemon_box_slot CHECK (box_slot BETWEEN 1 AND 30);
