-- A place's name without the booking site it is listed through, so "OYO 47006 Hotel Lotus" can be found
-- as a candidate for "Hotel Lotus". The prefixes follow MatchEvidence.AGGREGATORS.
CREATE OR REPLACE FUNCTION idi_match_name(input text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS
$$
SELECT regexp_replace(public.idi_normalize(input),
        '^(super oyo|super capital o|capital o|collection o|spot on|oyo|fabhotels? prime|fabhotels?|treebo trend|treebo|townhouse|flagship|zo rooms|vista rooms)( [0-9]+)? ',
        '')
$$;
