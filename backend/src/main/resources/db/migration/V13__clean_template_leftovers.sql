-- Dropped wiki templates used to leave empty brackets behind: "Booking office: 11A, Free School St, Kolkata ()" and
-- "a heritage cafe (see )". The importer no longer produces them (WikiText.cleanArtifacts); this removes the ones already
-- stored. Same rules as the importer. Rows whose text would become empty are left alone.
WITH cleaned AS (
    SELECT id,
           btrim(regexp_replace(regexp_replace(regexp_replace(value_text,
               '[ \t]*\((?:see|see also|cf\.?|compare|also)[ \t,;:]*\)', '', 'gi'),
               '[ \t]*[(\[][ \t]*[)\]]', '', 'g'),
               '[ \t]+([,.;:])', '\1', 'g')) AS text
    FROM fact
    WHERE value_text ~* '\((?:see|see also|cf\.?|compare|also)[ \t,;:]*\)' OR value_text ~ '[(\[][ \t]*[)\]]'
)
UPDATE fact f SET value_text = c.text, updated_at = now()
FROM cleaned c
WHERE f.id = c.id AND length(c.text) > 0 AND c.text <> f.value_text;
