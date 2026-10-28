-- An admin can move a listing to the section where it belongs (a shop filed under Eat, a restaurant under Stay).
-- The source keeps its own filing; the override is what readers see, and re-imports respect it.
ALTER TABLE guide_entry ADD COLUMN section_override text
    CHECK (section_override IN ('eat', 'drink', 'stay', 'see', 'do', 'buy'));
