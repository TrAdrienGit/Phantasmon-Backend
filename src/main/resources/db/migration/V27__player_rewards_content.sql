-- Rewards (TODO-37, 2026-11-11): besides looks, a reserved battle intro or ambiance (INTRO, AMBIANCE), named by its
-- namespaced id ("phantasmon_events:gym_leader"): longer values than a look's.
ALTER TABLE player_rewards ALTER COLUMN value TYPE VARCHAR(130);
