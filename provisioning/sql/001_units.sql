-- HAMS provisioning registry. The authoritative free/claimed state lives here,
-- NOT in Wialon. Seeded from Wialon (HAMS-ready group) by the n8n seeding flow;
-- the per-device claim reads/writes this table only.

CREATE TABLE IF NOT EXISTS units (
    unique_id          TEXT PRIMARY KEY,              -- Wialon unit IPS unique id (e.g. OC154_H001)
    name               TEXT,
    claimed            BOOLEAN NOT NULL DEFAULT false,
    device_fingerprint TEXT UNIQUE,                   -- phone ANDROID_ID; multiple NULLs allowed
    status             TEXT NOT NULL DEFAULT 'active', -- 'active' | 'retired'
    last_seen          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Fast "next free unit" lookup.
CREATE INDEX IF NOT EXISTS idx_units_free
    ON units (unique_id)
    WHERE claimed = false AND status = 'active';
