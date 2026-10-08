-- Phantasmon Network, several hubs (D-35, 2026-10-08): admins create hubs, each with its own size (width x, height y,
-- length z) and its own build (hub_schematics/hub_<name>/). Every existing anchor belonged to the one Global Hub: it
-- becomes the hub "global" (21 x 21 x 21, the former phantasmon.hub.anchor-size). A player may now pose one anchor
-- per hub instead of one in all.
CREATE TABLE hubs (
    uuid        UUID PRIMARY KEY,
    name        VARCHAR(32) NOT NULL,
    size_x      SMALLINT NOT NULL CHECK (size_x BETWEEN 3 AND 64),
    size_y      SMALLINT NOT NULL CHECK (size_y BETWEEN 3 AND 64),
    size_z      SMALLINT NOT NULL CHECK (size_z BETWEEN 3 AND 64),
    created_by  UUID REFERENCES players(uuid) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_hub_name ON hubs(lower(name));

INSERT INTO hubs (uuid, name, size_x, size_y, size_z)
VALUES ('6c0b5d2e-3b1a-4f0e-9a51-000000000001', 'global', 21, 21, 21);

ALTER TABLE hub_anchors ADD COLUMN hub_uuid UUID REFERENCES hubs(uuid) ON DELETE CASCADE;
UPDATE hub_anchors SET hub_uuid = '6c0b5d2e-3b1a-4f0e-9a51-000000000001';
ALTER TABLE hub_anchors ALTER COLUMN hub_uuid SET NOT NULL;

ALTER TABLE hub_anchors DROP CONSTRAINT uq_hub_anchor_owner;
ALTER TABLE hub_anchors ADD CONSTRAINT uq_hub_anchor_owner_hub UNIQUE (owner_uuid, hub_uuid);
CREATE INDEX idx_hub_anchor_hub ON hub_anchors(hub_uuid);
