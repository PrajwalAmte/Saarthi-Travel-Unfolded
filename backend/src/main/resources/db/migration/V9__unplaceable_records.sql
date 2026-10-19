-- Records whose coordinates are placeholders (many unrelated places stacked on one exact point,
-- typically a town centroid) are kept for provenance but not mapped, matched or published.
ALTER TABLE poi_source_record DROP CONSTRAINT poi_source_record_match_confidence_check;
ALTER TABLE poi_source_record ADD CONSTRAINT poi_source_record_match_confidence_check
    CHECK (match_confidence IN ('source_identity', 'high', 'possible', 'new', 'unplaceable'));
CREATE INDEX poi_source_record_point_idx ON poi_source_record (source_id, round(latitude::numeric, 5), round(longitude::numeric, 5))
    WHERE deleted_at IS NULL;
