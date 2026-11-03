-- Coordinates confirmed by two independent sources: Wikidata holds several statements for the item and one agrees
-- with the place's Wikivoyage article. Later re-indexing keeps these instead of sampling a statement again.
ALTER TABLE place DROP CONSTRAINT place_coordinates_source_check;
ALTER TABLE place ADD CONSTRAINT place_coordinates_source_check
    CHECK (coordinates_source IN ('wikidata', 'wikidata_headquarters', 'wikivoyage', 'openstreetmap', 'community', 'two_sources'));
