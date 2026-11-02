-- Hub chat moderation (TODO-31): players an admin muted in the hubs' chat, until muted_until.
CREATE TABLE hub_chat_mutes (
    player_uuid UUID PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    muted_until TIMESTAMPTZ NOT NULL,
    muted_by    UUID NOT NULL,
    muted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
