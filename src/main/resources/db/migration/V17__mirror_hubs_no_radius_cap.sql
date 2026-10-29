-- Mirror hubs without a size limit (D-38, Adrien 2026-10-29): admins only, their responsibility. size_x and size_z
-- (2 * radius + 1) stay SMALLINTs, so the radius stays below 16383.
ALTER TABLE hubs DROP CONSTRAINT hubs_size_check;
ALTER TABLE hubs ADD CONSTRAINT hubs_size_check CHECK (
    (mirror AND radius >= 1 AND size_x = 2 * radius + 1 AND size_z = 2 * radius + 1 AND size_y = 384)
    OR (NOT mirror AND radius IS NULL AND size_x BETWEEN 3 AND 64 AND size_y BETWEEN 3 AND 64 AND size_z BETWEEN 3 AND 64));
