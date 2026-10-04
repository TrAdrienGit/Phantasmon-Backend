-- Cobblemon has a single Hidden Power move, "hiddenpower", whose type comes from the IVs. Showdown's per-type
-- variants ("Hidden Power Ice"...) all share that id (realMove), so "hiddenpowerice" & co. do not exist in Cobblemon:
-- Pokémon imported with them before the client fix (2026-10-03) were "not recognized" and kept out of battles.
-- Every variant id in data.moves becomes "hiddenpower", order and other moves kept; the type is the IVs' one.
-- Re-runnable: only rows that still hold a variant id are touched.

UPDATE pokemon
SET data = jsonb_set(data, '{moves}', (
        SELECT jsonb_agg(CASE WHEN m.move ~ '^hiddenpower[a-z]+$' THEN to_jsonb('hiddenpower'::text) ELSE to_jsonb(m.move) END
                         ORDER BY m.ord)
        FROM jsonb_array_elements_text(data->'moves') WITH ORDINALITY AS m(move, ord)))
WHERE jsonb_typeof(data->'moves') = 'array'
  AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(data->'moves') AS m(move) WHERE m.move ~ '^hiddenpower[a-z]+$');
