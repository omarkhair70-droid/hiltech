-- Allocate human-readable quote numbers atomically per company and calendar year.
-- Existing quote numbers and released snapshots are never rewritten.
CREATE TABLE commercial_quote_number_sequence (
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    calendar_year integer NOT NULL CHECK (calendar_year BETWEEN 1900 AND 9999),
    last_number bigint NOT NULL CHECK (last_number > 0),
    PRIMARY KEY (organization_id,calendar_year)
);
CREATE FUNCTION commercial_allocate_quote_code(p_company uuid,p_year integer,p_prefix text)
RETURNS text LANGUAGE plpgsql AS $$
DECLARE
    serial_number bigint;
    candidate text;
BEGIN
    IF p_prefix IS NULL OR p_prefix !~ '^[A-Z0-9-]{1,12}$' THEN
        RAISE EXCEPTION 'Invalid commercial quote prefix';
    END IF;
    LOOP
        INSERT INTO commercial_quote_number_sequence(organization_id,calendar_year,last_number)
        VALUES(p_company,p_year,1)
        ON CONFLICT(organization_id,calendar_year) DO UPDATE
        SET last_number=commercial_quote_number_sequence.last_number+1
        RETURNING last_number INTO serial_number;
        candidate := p_prefix || '-' || p_year::text || '-' ||
            lpad(serial_number::text,greatest(6,length(serial_number::text)),'0');
        EXIT WHEN NOT EXISTS (
            SELECT 1 FROM commercial_quote_version q
            WHERE q.organization_id=p_company AND q.quote_code=candidate
        );
    END LOOP;
    RETURN candidate;
END;
$$;
