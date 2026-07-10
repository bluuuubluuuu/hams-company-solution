-- Seeding UPSERT. Adds new unit ids as free; for an existing id it ONLY refreshes
-- the name. It deliberately does NOT touch claimed / device_fingerprint / status,
-- so re-running the seeding flow never resets a live device assignment.

CREATE OR REPLACE FUNCTION seed_unit(p_unique_id text, p_name text)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO "G_PM_IT_IOT_HAMS_UNITS" (unique_id, name, status)
    VALUES (p_unique_id, p_name, 'active')
    ON CONFLICT (unique_id) DO UPDATE
        SET name = EXCLUDED.name, updated_at = now();
$$;
