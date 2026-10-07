-- Phantasmon Network (D-30, corrected 2026-10-07): an anchor is shared by all the players of its server (never across
-- servers), so it is listed per server again and its name is unique per server again. V12 dropped both indexes;
-- anchors named alike in the meantime get a number appended first, so the unique index can be built.
WITH duplicates AS (
    SELECT uuid, row_number() OVER (PARTITION BY server_fingerprint, lower(name) ORDER BY created_at, uuid) AS rank
    FROM hub_anchors
)
UPDATE hub_anchors h
SET name = left(h.name, 28) || ' ' || d.rank
FROM duplicates d
WHERE h.uuid = d.uuid AND d.rank > 1;

CREATE UNIQUE INDEX uq_hub_anchor_server_name ON hub_anchors(server_fingerprint, lower(name));
CREATE INDEX idx_hub_anchor_server_dimension ON hub_anchors(server_fingerprint, dimension);
