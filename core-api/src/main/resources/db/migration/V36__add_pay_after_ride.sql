-- Pay after the ride. A driver may let passengers book a ride without paying up front;
-- they pay once the booking ends, in the app or in cash to the driver. Nothing is held at
-- booking, so nothing can be released: when the booking ends it owes exactly what a booking
-- paid up front would have kept (PaymentService), and what it owes must be paid before the
-- passenger books again.

-- The driver's choice, per ride, as with instant booking.
ALTER TABLE rides ADD COLUMN pay_after_ride BOOLEAN NOT NULL DEFAULT false;

-- Still one intent per booking. A pay-after-ride intent is DEFERRED while the booking is
-- live, with no method, provider or reference because no money has moved. When the booking
-- ends it is DUE with what is owed and why, or VOID when nothing is; once paid it is
-- SETTLED like any other. For a settled one the ledger holds and captures exactly
-- amount_due, so HOLD = CAPTURE + RELEASE still holds, with nothing released. Paid in cash,
-- the money went hand to hand: the provider is CASH and nothing is paid out.
ALTER TABLE payment_intents
    ADD COLUMN deferred BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN amount_due NUMERIC(10, 2),
    ADD COLUMN due_reason TEXT
        CHECK (due_reason IN ('TRIP_COMPLETED', 'PASSENGER_NO_SHOW', 'PASSENGER_CANCELLED')),
    ALTER COLUMN method DROP NOT NULL,
    ALTER COLUMN provider DROP NOT NULL,
    ALTER COLUMN provider_ref DROP NOT NULL,
    DROP CONSTRAINT payment_intents_method_check,
    DROP CONSTRAINT payment_intents_status_check,
    ADD CONSTRAINT payment_intents_method_check CHECK (method IN ('CARD', 'UPI', 'WALLET', 'CASH')),
    ADD CONSTRAINT payment_intents_status_check
        CHECK (status IN ('HELD', 'SETTLED', 'DEFERRED', 'DUE', 'VOID')),
    -- How it was paid is known exactly when money has moved.
    ADD CONSTRAINT paid_says_how CHECK (CASE WHEN status IN ('DEFERRED', 'DUE', 'VOID')
        THEN method IS NULL AND provider IS NULL AND provider_ref IS NULL
        ELSE method IS NOT NULL AND provider IS NOT NULL AND provider_ref IS NOT NULL END),
    -- Only a pay-after-ride intent waits, owes or is paid in cash; it is never held.
    ADD CONSTRAINT only_deferred_waits CHECK (CASE WHEN deferred THEN status <> 'HELD'
        ELSE status IN ('HELD', 'SETTLED') AND amount_due IS NULL AND method <> 'CASH' END),
    -- What is owed is set with its reason when the booking ends, and kept once paid.
    ADD CONSTRAINT due_has_reason CHECK ((amount_due IS NULL) = (due_reason IS NULL)),
    ADD CONSTRAINT due_is_part_of_the_amount
        CHECK (amount_due IS NULL OR (amount_due > 0 AND amount_due <= amount)),
    ADD CONSTRAINT owed_when_due CHECK (CASE WHEN status IN ('DEFERRED', 'VOID') THEN amount_due IS NULL
        WHEN deferred THEN amount_due IS NOT NULL ELSE true END);

-- A passenger with something due cannot book: checked on every booking request.
CREATE INDEX idx_payment_intents_due ON payment_intents (passenger_id) WHERE status = 'DUE';
