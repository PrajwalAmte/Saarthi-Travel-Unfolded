-- Corrections found while integrating the sources.
--
-- Overture's places theme is not under one licence: Meta, Microsoft, PinMeTo, DAC and other
-- contributors publish under CDLA Permissive 2.0, Foursquare under Apache 2.0 (its NOTICE must
-- accompany redistribution) and AllThePlaces under CC0. Each stored record keeps its list of
-- contributing datasets, so the applicable terms can be traced per record.
UPDATE source SET
    licence_name = 'CDLA Permissive 2.0, Apache 2.0 (Foursquare) and CC0 (AllThePlaces), per contributing dataset',
    licence_url = 'https://docs.overturemaps.org/attribution/',
    attribution_text = 'Places data © Overture Maps Foundation, from Meta, Microsoft, Foursquare, AllThePlaces and other contributors.',
    usage_notes = 'Read from the release GeoParquet files around indexed destinations only. Every record keeps its Overture id, '
        || 'release, confidence and contributing datasets. Foursquare-derived records are redistributed under Apache 2.0 with '
        || 'Overture''s NOTICE (https://docs.overturemaps.org/attribution/).',
    updated_at = now()
WHERE id = 'overture-places';

-- The ASI number (Wikidata P1371) is carried by state-protected monuments as well as monuments of
-- national importance; the designation of each item is stored with it.
UPDATE source SET
    name = 'Protected monuments with ASI numbers (via Wikidata)',
    updated_at = now()
WHERE id = 'asi-heritage';
