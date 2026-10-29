-- Phantasmon Events, mirror hubs (D-38, 2026-10-29): an admin's hub whose terrain is the real ground of a zone of a
-- multiplayer server (its SOURCE anchor, one per hub), copied into single-player worlds (its COPY anchors, one per
-- admin). Ordinary hubs keep NORMAL anchors, one per player and hub.
ALTER TABLE hubs ADD COLUMN mirror BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE hub_anchors ADD COLUMN role VARCHAR(8) NOT NULL DEFAULT 'NORMAL'
    CHECK (role IN ('NORMAL', 'SOURCE', 'COPY'));

ALTER TABLE hub_anchors DROP CONSTRAINT uq_hub_anchor_owner_hub;
CREATE UNIQUE INDEX uq_hub_anchor_owner_hub ON hub_anchors(owner_uuid, hub_uuid) WHERE role <> 'SOURCE';
CREATE UNIQUE INDEX uq_hub_anchor_source ON hub_anchors(hub_uuid) WHERE role = 'SOURCE';
