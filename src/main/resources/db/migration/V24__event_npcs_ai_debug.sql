-- Event NPC: its AI explains each decision in the chat of the player battling it (debugging the Expert AI, 2026-11-03).
ALTER TABLE event_npcs ADD COLUMN ai_debug BOOLEAN NOT NULL DEFAULT FALSE;
