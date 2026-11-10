-- D-41 / TODO-35: the core no longer knows mirror hubs (Phantasmon Events, D-38). A hub has a generic kind
-- (NORMAL, or one an add-on defines: MIRROR), an anchor a generic role (NORMAL, or one the hub's kind defines).
-- A mirror hub's radius was redundant with its size (side = 2 * radius + 1): Events reads it from size_x.
-- Events' own migrations (db/events) recreate its anchor rules (one SOURCE per hub, one COPY per admin).

ALTER TABLE hubs ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
UPDATE hubs SET kind = 'MIRROR' WHERE mirror;

ALTER TABLE hubs DROP CONSTRAINT IF EXISTS hubs_size_check;
ALTER TABLE hubs DROP COLUMN mirror;
ALTER TABLE hubs DROP COLUMN radius;
-- An ordinary hub is 3 to 64 blocks a side; another kind's sizes are its add-on's business.
ALTER TABLE hubs ADD CONSTRAINT hubs_size_check CHECK (
    (kind = 'NORMAL' AND size_x BETWEEN 3 AND 64 AND size_y BETWEEN 3 AND 64 AND size_z BETWEEN 3 AND 64)
    OR (kind <> 'NORMAL' AND size_x >= 1 AND size_y >= 1 AND size_z >= 1));

ALTER TABLE hub_anchors DROP CONSTRAINT IF EXISTS hub_anchors_role_check;
ALTER TABLE hub_anchors ALTER COLUMN role TYPE VARCHAR(16);
DROP INDEX IF EXISTS uq_hub_anchor_source;
DROP INDEX IF EXISTS uq_hub_anchor_owner_hub;
-- One anchor per player and ordinary hub (D-35); other roles: the add-on's rules.
CREATE UNIQUE INDEX uq_hub_anchor_owner_hub ON hub_anchors(owner_uuid, hub_uuid) WHERE role = 'NORMAL';
