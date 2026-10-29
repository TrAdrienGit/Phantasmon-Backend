-- Mirror hubs by radius (D-38, Adrien 2026-10-29): a mirror hub is a square of side 2 * radius + 1 around its centre,
-- the whole height of the world (y -64 to 319, 384 blocks); admins resize it by its radius (1 to 64). Ordinary hubs
-- keep their sizes of 3 to 64.
ALTER TABLE hubs ADD COLUMN radius SMALLINT;
ALTER TABLE hubs DROP CONSTRAINT IF EXISTS hubs_size_x_check;
ALTER TABLE hubs DROP CONSTRAINT IF EXISTS hubs_size_y_check;
ALTER TABLE hubs DROP CONSTRAINT IF EXISTS hubs_size_z_check;
-- Mirror hubs made before (tests): the radius that covers their former width and length.
UPDATE hubs SET radius = LEAST(64, GREATEST(1, GREATEST(size_x, size_z) / 2)) WHERE mirror;
UPDATE hubs SET size_x = 2 * radius + 1, size_z = 2 * radius + 1, size_y = 384 WHERE mirror;
ALTER TABLE hubs ADD CONSTRAINT hubs_size_check CHECK (
    (mirror AND radius BETWEEN 1 AND 64 AND size_x = 2 * radius + 1 AND size_z = 2 * radius + 1 AND size_y = 384)
    OR (NOT mirror AND radius IS NULL AND size_x BETWEEN 3 AND 64 AND size_y BETWEEN 3 AND 64 AND size_z BETWEEN 3 AND 64));
