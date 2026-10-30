-- Trainers' animation profiles (Cobblemon Spectacle phase E3): each player's chosen animation per battle moment
-- (guard, send_out, recall, attack, victory, defeat), shown to the other players in battles and hubs.
CREATE TABLE trainer_profiles (
    player_uuid UUID PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    profile     JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
