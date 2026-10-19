-- Where a destination's coordinates came from. Wikidata is the default; when it has none, or a
-- district's point contradicts its own headquarters, the point is taken from another named source
-- instead of being guessed.
ALTER TABLE place ADD COLUMN coordinates_source text
    CHECK (coordinates_source IN ('wikidata', 'wikidata_headquarters', 'wikivoyage', 'openstreetmap', 'community'));
UPDATE place SET coordinates_source = CASE WHEN wikidata_id IS NOT NULL THEN 'wikidata' ELSE 'community' END
WHERE latitude IS NOT NULL;
