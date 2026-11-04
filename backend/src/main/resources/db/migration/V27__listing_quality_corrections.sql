-- Corrections from an outside review of what the guides actually show. New imports already follow
-- these rules; this brings stored data in line without waiting for the next import of each source.

-- Overture's "historic_site" mostly holds apartment blocks, housing societies and localities, so it
-- is no longer imported. A place another source also describes stays on the map.
UPDATE poi_source_record SET deleted_at = now()
WHERE source_id = 'overture-places' AND category = 'heritage_site' AND deleted_at IS NULL;

UPDATE poi p SET status = 'removed', updated_at = now()
WHERE p.status = 'active'
  AND EXISTS (SELECT 1 FROM poi_source_record r
              WHERE r.poi_id = p.id AND r.source_id = 'overture-places' AND r.category = 'heritage_site')
  AND NOT EXISTS (SELECT 1 FROM poi_source_record r WHERE r.poi_id = p.id AND r.deleted_at IS NULL);

-- Interests on businesses read from a name (Hotel Grand Park, Church Street) rather than from an
-- ordinary lower-case word in the description, and shopping on stays ("boutique hotel"). See AttributeDeriver.
WITH described AS (
    SELECT e.id, e.section, e.attributes,
           coalesce((SELECT f.value_text FROM fact f
                     WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published'
                       AND coalesce(f.ingest_key, '') NOT LIKE 'ai:%' LIMIT 1), '') AS description
    FROM guide_entry e
    WHERE e.section IN ('eat', 'drink', 'stay', 'buy') AND jsonb_typeof(e.attributes -> 'interests') = 'array'
), kept AS (
    SELECT d.id,
           (SELECT coalesce(jsonb_agg(i.el ORDER BY i.ord), '[]'::jsonb)
            FROM jsonb_array_elements(d.attributes -> 'interests') WITH ORDINALITY AS i(el, ord)
            WHERE NOT (i.el ->> 'basis' LIKE 'Source text mentions “%'
                       AND i.el ->> 'tag' <> 'nightlife'
                       AND ((d.section = 'stay' AND i.el ->> 'tag' = 'shopping')
                            OR d.description !~ ('(^|[^[:alnum:]])' || substring(i.el ->> 'basis' from '“(.*)”') || '($|[^[:alnum:]])'))))
               AS interests
    FROM described d
)
UPDATE guide_entry e
SET attributes = CASE WHEN k.interests = '[]'::jsonb THEN e.attributes - 'interests'
                      ELSE jsonb_set(e.attributes, '{interests}', k.interests) END
FROM kept k
WHERE k.id = e.id AND k.interests <> e.attributes -> 'interests';

-- Wikivoyage topic articles are not destinations ("festivals in Kerala", "travel warning").
UPDATE place SET lifecycle_status = 'removed', is_destination = false, updated_at = now()
WHERE lifecycle_status = 'active' AND is_destination AND name ~ '^[a-z]';

-- Listings of a place that is no longer a destination. This reason lets a later import restore
-- them if the place ever returns to the index.
UPDATE guide_entry e SET status = 'hidden', status_reason = 'removed_from_source', updated_at = now()
FROM place p
WHERE p.id = e.destination_id AND p.lifecycle_status = 'removed' AND e.status = 'published';

-- "Too thin" was left to the model, which marked about a third of the shortest listings. Short or
-- empty descriptions are now marked by rule (ListingReviewHandler.THIN_WORDS).
UPDATE ai_review r SET issues = array_append(r.issues, 'too_thin')
WHERE r.status = 'clear' AND NOT 'too_thin' = ANY (r.issues)
  AND coalesce((SELECT max(array_length(regexp_split_to_array(trim(f.value_text), '\s+'), 1)) FROM fact f
                WHERE f.entry_id = r.entry_id AND f.kind = 'description' AND f.status = 'published'), 0) <= 5;
