-- Extensions -----------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Normalisation used for name matching, alias lookup and request de-duplication.
-- unaccent() is STABLE, so it is wrapped in an IMMUTABLE function with an explicit
-- dictionary to make it usable in generated columns and indexes.
CREATE OR REPLACE FUNCTION idi_normalize(input text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS
$$
SELECT btrim(regexp_replace(
        regexp_replace(lower(public.unaccent('public.unaccent'::regdictionary, input)), '[[:punct:]]+', ' ', 'g'),
        '\s+', ' ', 'g'))
$$;

-- Source registry -------------------------------------------------------------
-- One row per external or internal source of information. Licence and attribution
-- metadata is stored alongside operational sync state so it can be shown publicly.
CREATE TABLE source (
    id                    text PRIMARY KEY,
    name                  text        NOT NULL,
    category              text        NOT NULL CHECK (category IN ('open_data', 'open_guide', 'climate_data', 'media', 'community')),
    access_method         text        NOT NULL,
    base_url              text,
    licence_name          text        NOT NULL,
    licence_url           text,
    attribution_text      text        NOT NULL,
    share_alike           boolean     NOT NULL DEFAULT false,
    usage_notes           text,
    rate_limit_policy     text,
    refresh_interval_days integer     NOT NULL CHECK (refresh_interval_days > 0),
    enabled               boolean     NOT NULL DEFAULT true,
    last_fetch_at         timestamptz,
    last_success_at       timestamptz,
    last_error            text,
    last_error_at         timestamptz,
    consecutive_failures  integer     NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

INSERT INTO source (id, name, category, access_method, base_url, licence_name, licence_url, attribution_text,
                    share_alike, usage_notes, rate_limit_policy, refresh_interval_days)
VALUES ('wikidata', 'Wikidata', 'open_data',
        'SPARQL queries against query.wikidata.org and the wbgetentities / wbsearchentities API at www.wikidata.org/w/api.php',
        'https://www.wikidata.org', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/',
        'Place identities, names, aliases, coordinates and administrative relationships from Wikidata.',
        false,
        'Structured data is CC0. Used for the nationwide destination index, aliases and administrative hierarchy.',
        'Serial requests with a descriptive User-Agent; SPARQL queries are kept small and are not run in parallel.',
        30),
       ('wikivoyage-en', 'English Wikivoyage', 'open_guide',
        'MediaWiki Action API (action=query&prop=revisions) at en.wikivoyage.org/w/api.php; wikitext is parsed deterministically',
        'https://en.wikivoyage.org', 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/',
        'Guide listings and travel text from English Wikivoyage contributors, used under CC BY-SA 4.0.',
        true,
        'Text excerpts are reproduced with attribution and a link to the exact article revision. Adapted text remains CC BY-SA 4.0.',
        'Serial requests with a descriptive User-Agent and maxlag; one article at a time.',
        7),
       ('open-meteo', 'Open-Meteo Historical Weather API', 'climate_data',
        'HTTPS JSON API at archive-api.open-meteo.com/v1/archive (ERA5 reanalysis), aggregated into monthly climate statistics',
        'https://open-meteo.com', 'CC BY 4.0', 'https://creativecommons.org/licenses/by/4.0/',
        'Weather data by Open-Meteo.com (ERA5 reanalysis, Copernicus Climate Change Service).',
        false,
        'Free API for non-commercial use; only derived monthly aggregates are stored.',
        'Well under the free-tier limit of 10,000 calls per day; one call per destination per refresh.',
        365),
       ('wikimedia-commons', 'Wikimedia Commons', 'media',
        'MediaWiki Action API (prop=imageinfo with extmetadata) at commons.wikimedia.org/w/api.php for images referenced by Wikidata (P18)',
        'https://commons.wikimedia.org', 'Per-file free licence', 'https://commons.wikimedia.org/wiki/Commons:Licensing',
        'Images from Wikimedia Commons, credited to their authors under each file''s licence.',
        false,
        'Only the thumbnail URL, author credit and licence are stored. Images are hot-linked from upload.wikimedia.org.',
        'Serial requests with a descriptive User-Agent.',
        90),
       ('community', 'Community contributors', 'community',
        'Contributions and verifications submitted by signed-in contributors and approved through moderation',
        NULL, 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/',
        'Corrections, confirmations and local tips from the platform''s contributors.',
        true,
        'Contributors accept the contributor licence before their first submission.',
        'Per-user and per-IP rate limits are enforced by the API.',
        3650);
