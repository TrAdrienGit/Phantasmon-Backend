-- DEBT-1: the last camelCase keys of pokemon.data become snake_case like the rest of the API
-- (heldItem -> held_item, teraType -> tera_type; friendship is already one word). Map keys escape the global
-- SNAKE_CASE naming strategy, which is how they slipped through. The client is updated in the same release.
-- Re-runnable: each statement only touches rows that still have an old key.

UPDATE pokemon
SET data = (data - 'heldItem') || jsonb_build_object('held_item', data->'heldItem')
WHERE data ? 'heldItem';

UPDATE pokemon
SET data = (data - 'teraType') || jsonb_build_object('tera_type', data->'teraType')
WHERE data ? 'teraType';

-- Responses replayed by POST /pokemon idempotency carry the same data map.
UPDATE idempotency_keys
SET response_snapshot = jsonb_set(response_snapshot, '{data}',
        ((response_snapshot->'data') - 'heldItem') || jsonb_build_object('held_item', response_snapshot->'data'->'heldItem'))
WHERE response_snapshot->'data' ? 'heldItem';

UPDATE idempotency_keys
SET response_snapshot = jsonb_set(response_snapshot, '{data}',
        ((response_snapshot->'data') - 'teraType') || jsonb_build_object('tera_type', response_snapshot->'data'->'teraType'))
WHERE response_snapshot->'data' ? 'teraType';
