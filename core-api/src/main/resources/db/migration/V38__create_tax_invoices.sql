-- Tax invoices for VAYORA's own charges (feature 50): the service fee on a booking, or a pass.
-- Off until a GST registration is configured, and only for real money. What an invoice said is
-- kept as it was issued, details and rate included, because neither may change afterwards.
CREATE TABLE tax_invoices (
    id             BIGSERIAL PRIMARY KEY,
    number         TEXT NOT NULL UNIQUE,
    financial_year TEXT NOT NULL,
    serial         INT NOT NULL,
    booking_id     BIGINT UNIQUE REFERENCES bookings(id),
    pass_id        BIGINT UNIQUE REFERENCES passes(id),
    issued_at      TIMESTAMPTZ NOT NULL,
    gstin          TEXT NOT NULL,
    legal_name     TEXT NOT NULL,
    address        TEXT NOT NULL,
    sac_code       TEXT NOT NULL,
    rate           NUMERIC(5, 4) NOT NULL,
    taxable_value  NUMERIC(10, 2) NOT NULL,
    cgst           NUMERIC(10, 2) NOT NULL,
    sgst           NUMERIC(10, 2) NOT NULL,
    igst           NUMERIC(10, 2) NOT NULL,
    UNIQUE (financial_year, serial),
    CHECK ((booking_id IS NULL) <> (pass_id IS NULL))
);

-- Numbers run on without a gap within a financial year; this row is locked while one is taken.
CREATE TABLE tax_invoice_series (
    financial_year TEXT PRIMARY KEY,
    last_serial    INT NOT NULL
);

CREATE FUNCTION tax_invoices_are_final() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tax_invoices cannot be changed once issued';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tax_invoices_no_rewrite
    BEFORE UPDATE OR DELETE ON tax_invoices
    FOR EACH ROW EXECUTE FUNCTION tax_invoices_are_final();
