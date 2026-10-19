-- Exact-name lookups used by duplicate detection (same name and group close together) and by
-- the quality evaluation's residual-duplicate check, which otherwise has to self-join every POI.
CREATE INDEX poi_name_group_idx ON poi (name_normalized, category_group) WHERE status = 'active';
