-- HAMS provisioning registry. The authoritative free/claimed state lives here,
-- NOT in Wialon. Seeded from Wialon (HAMS-ready group) by the n8n seeding flow;
-- the per-device claim reads/writes this table only.

CREATE TABLE IF NOT EXISTS "G_PM_IT_IOT_HAMS_UNITS" (
    unique_id          TEXT PRIMARY KEY,              -- Wialon unit IPS unique id (e.g. OC154_H001)
    name               TEXT,
    claimed            BOOLEAN NOT NULL DEFAULT false,
    device_fingerprint TEXT UNIQUE,                   -- phone ANDROID_ID; multiple NULLs allowed
    status             TEXT NOT NULL DEFAULT 'active', -- 'active' | 'retired'
    last_seen          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index over the free, in-service units only.
--
-- CURRENTLY UNUSED. No routine queries `WHERE claimed = false AND status =
-- 'active'`: pairing is office-manual, so manual_claim() receives the exact
-- unit id and looks it up by primary key. Nothing asks the database to pick
-- a free unit.
--
-- Retained because it is near-free to maintain at this row count (one row per
-- handset), and it is what a future "auto-assign the next free unit" flow or a
-- "how many units are free?" dashboard would ride on. Do not assume pairing
-- depends on it.
CREATE INDEX IF NOT EXISTS "G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"
    ON "G_PM_IT_IOT_HAMS_UNITS" (unique_id)
    WHERE claimed = false AND status = 'active';
