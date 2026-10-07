-- Phantasmon Network (D-30): a Hub Anchor is personal, seen and used by its owner only. Names no longer need to be
-- unique on a server, and anchors are never listed per server.
DROP INDEX IF EXISTS uq_hub_anchor_server_name;
DROP INDEX IF EXISTS idx_hub_anchor_server_dimension;
