-- =====================================================================
-- Room pricing rules number the days of the week 0 = Sunday … 6 = Saturday,
-- which is what the service validates and what the dashboard sends. V1's
-- CHECK allowed 1..7 instead, so a Sunday rule (0) could never be saved:
-- the service accepted it and the database refused it with a bare 409.
--
-- Rows stored under the old CHECK hold 1..7. Values 1..6 already mean
-- Monday..Saturday under both readings; only 7 (the old Sunday, which the
-- dashboard could never have written, but SQL could) is moved to 0.
--
-- chk_avail_dow on room_availability_slots is left alone: nothing writes
-- availability slots yet, and changing its numbering is a separate decision.
-- =====================================================================

UPDATE room_pricing_rules SET day_of_week = 0 WHERE day_of_week = 7;

ALTER TABLE room_pricing_rules DROP CONSTRAINT IF EXISTS chk_price_rule_dow;
ALTER TABLE room_pricing_rules
    ADD CONSTRAINT chk_price_rule_dow CHECK (day_of_week IS NULL OR day_of_week BETWEEN 0 AND 6);
