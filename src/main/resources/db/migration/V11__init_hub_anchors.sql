-- Phantasmon Network, step N1 (network-cahier-des-charges.md §5.2, D-28): a Hub Anchor is a 21x21x21 cube placed by a
-- player on their Minecraft server, the doorway to the shared Global Hub. Any player may create one, one at most.
-- The server is known only by its fingerprint (D-18), never by its address.
CREATE TABLE hub_anchors (
    uuid                UUID PRIMARY KEY,
    owner_uuid          UUID NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    name                VARCHAR(32) NOT NULL,
    server_fingerprint  VARCHAR(128) NOT NULL,
    dimension           VARCHAR(128) NOT NULL,
    origin_x            DOUBLE PRECISION NOT NULL,
    origin_y            DOUBLE PRECISION NOT NULL,
    origin_z            DOUBLE PRECISION NOT NULL,
    -- Snapped to a quarter turn so every anchor's cube stays aligned with the block grid.
    yaw                 SMALLINT NOT NULL CHECK (yaw IN (0, 90, 180, 270)),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_hub_anchor_owner UNIQUE (owner_uuid)
);

CREATE UNIQUE INDEX uq_hub_anchor_server_name ON hub_anchors(server_fingerprint, lower(name));
CREATE INDEX idx_hub_anchor_server_dimension ON hub_anchors(server_fingerprint, dimension);
