-- The last-mile gap between where a booking's passenger starts and their boarding
-- stop, recorded once at booking request time (never updated) so the durability
-- model can use it as a covariate. Feature 91 already shows this gap and the access
-- options at search time; this persists what was shown for the one booking it led to.
--
-- last_mile_mode_shown is the most useful access option offered alongside the stop
-- itself: 'walk' when none was, else whichever of 'rickshaw' or 'bus' was shown.
-- There is no separate "mode available" column: that boolean feature is derived from
-- this one (mode_shown <> 'walk') wherever it is needed, so there is one fact, not two.
ALTER TABLE bookings
    ADD COLUMN last_mile_distance_m INTEGER CHECK (last_mile_distance_m >= 0),
    ADD COLUMN last_mile_mode_shown TEXT CHECK (last_mile_mode_shown IN ('walk', 'rickshaw', 'bus'));
