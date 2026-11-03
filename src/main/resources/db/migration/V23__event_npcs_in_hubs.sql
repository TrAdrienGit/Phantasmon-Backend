-- An event NPC may belong to a hub (Adrien 2026-11-03): then x, y, z, yaw are its place IN the hub (hub coordinates:
-- centre 0, 0, y from the anchor's floor) and every client shows it in each of that hub's zones it has (the source,
-- the copies, the ordinary anchors). server_fingerprint and dimension keep where it was posed.
ALTER TABLE event_npcs ADD COLUMN hub_uuid UUID REFERENCES hubs(uuid) ON DELETE CASCADE;
CREATE INDEX event_npcs_hub ON event_npcs (hub_uuid);
