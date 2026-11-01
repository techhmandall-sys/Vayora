-- A person's check of what a driver declared (features 7, 8, 35 and 58). No identity provider
-- or insurer is connected, so the driver shows the original to an administrator, in person or
-- on a video call, and the administrator records the outcome. There is no column for a
-- document's number or a copy of it: only that a person saw it, who, and when.
CREATE TABLE document_checks (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('IDENTITY', 'INSURANCE')),
    driver_id    BIGINT NOT NULL REFERENCES users(id),
    -- The car, for insurance. An identity check has none.
    vehicle_id   BIGINT REFERENCES vehicles(id),
    status       TEXT NOT NULL DEFAULT 'WAITING'
                 CHECK (status IN ('WAITING', 'CHECKED', 'REFUSED', 'WITHDRAWN')),
    requested_at TIMESTAMPTZ NOT NULL,
    decided_at   TIMESTAMPTZ,
    decided_by   BIGINT REFERENCES users(id),
    -- Why it could not be confirmed, or anything the administrator adds. Shown to the driver.
    note         TEXT,
    CHECK ((kind = 'INSURANCE') = (vehicle_id IS NOT NULL)),
    CHECK ((status IN ('CHECKED', 'REFUSED')) = (decided_at IS NOT NULL))
);

-- One live check for each thing declared. A refusal is not live: the driver may ask again.
CREATE UNIQUE INDEX uq_document_checks_live
    ON document_checks (driver_id, kind, COALESCE(vehicle_id, 0))
    WHERE status IN ('WAITING', 'CHECKED');

-- The administrators' queue, oldest first.
CREATE INDEX idx_document_checks_waiting ON document_checks (requested_at) WHERE status = 'WAITING';

-- For search results and the verified filter.
CREATE INDEX idx_document_checks_checked ON document_checks (driver_id, kind) WHERE status = 'CHECKED';
CREATE INDEX idx_document_checks_vehicle ON document_checks (vehicle_id) WHERE status = 'CHECKED';
