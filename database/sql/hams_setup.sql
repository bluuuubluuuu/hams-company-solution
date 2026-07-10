-- =====================================================================
-- HAMS provisioning schema - consolidated setup script
-- =====================================================================
-- Run ONCE on a fresh, empty database:
--     psql "$PROV_DB_URL" -f hams_setup.sql
--
-- Equivalent to running the 7 numbered scripts in sql/history/ in this
-- order: 001, 003, 004, 005, 007, 006, 008.
--   - 002 does not exist (withdrawn during development).
--   - 007 runs before 006: 006 reads columns that 007 adds.
--
-- Idempotent: safe to re-run. Tables use CREATE TABLE IF NOT EXISTS,
-- functions use CREATE OR REPLACE. Re-running never resets a live
-- device pairing.
--
-- NAMING (SOP): tables and indexes are QUOTED UPPERCASE with the
-- G_PM_IT_IOT_HAMS_ prefix. Every reference must be quoted:
--     SELECT * FROM "G_PM_IT_IOT_HAMS_UNITS";   -- correct
--     SELECT * FROM G_PM_IT_IOT_HAMS_UNITS;       -- ERROR (folds to lowercase)
-- Functions and columns remain unquoted lowercase.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 001_units.sql
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 003_seed_unit.sql
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 004_admin_otp.sql
-- ---------------------------------------------------------------------
-- Admin OTP store for the n8n provisioning backend (company one-off).
-- Codes are short-lived + single-use; consumed only on a successful privileged
-- action. Single-admin scope; no hashing (showcase). Translated to plpgsql so the
-- thin n8n workflows just SELECT these functions.

CREATE TABLE IF NOT EXISTS "G_PM_IT_IOT_HAMS_ADMIN_OTP" (
    code        TEXT PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);

-- Issue a new OTP (6-digit). Purges expired rows first (keeps the table small and
-- avoids PK collisions with stale codes). Returns the plaintext code.
CREATE OR REPLACE FUNCTION issue_otp(p_ttl_minutes int DEFAULT 10)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    v_code text;
BEGIN
    DELETE FROM "G_PM_IT_IOT_HAMS_ADMIN_OTP" WHERE expires_at < now();
    v_code := lpad((floor(random() * 1000000))::int::text, 6, '0');
    INSERT INTO "G_PM_IT_IOT_HAMS_ADMIN_OTP"(code, expires_at)
    VALUES (v_code, now() + make_interval(mins => p_ttl_minutes));
    RETURN v_code;
END;
$$;

-- Validate WITHOUT consuming: true if the code is currently usable.
CREATE OR REPLACE FUNCTION otp_is_valid(p_otp text)
RETURNS boolean
LANGUAGE sql
AS $$
    SELECT EXISTS (
        SELECT 1 FROM "G_PM_IT_IOT_HAMS_ADMIN_OTP"
         WHERE code = p_otp AND used_at IS NULL AND expires_at > now()
    );
$$;

-- Consume (single-use): atomically mark used; true only if it was still valid.
CREATE OR REPLACE FUNCTION consume_otp(p_otp text)
RETURNS boolean
LANGUAGE sql
AS $$
    WITH upd AS (
        UPDATE "G_PM_IT_IOT_HAMS_ADMIN_OTP" SET used_at = now()
         WHERE code = p_otp AND used_at IS NULL AND expires_at > now()
        RETURNING code
    )
    SELECT EXISTS (SELECT 1 FROM upd);
$$;

-- ---------------------------------------------------------------------
-- 005_manual_provision.sql
-- ---------------------------------------------------------------------
-- Manual-pairing provisioning functions for the n8n backend. Guard/OTP logic lives
-- in plpgsql (atomic, testable) so the n8n workflows stay thin. Each returns
-- jsonb {status, ...} that n8n maps to an HTTP code.
-- OTP is validated first and consumed ONLY on success (a failed guard never burns a code).

-- Bind a SPECIFIC unit to a device fingerprint (admin-supplied unit id).
CREATE OR REPLACE FUNCTION manual_claim(p_unique_id text, p_fingerprint text, p_otp text)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    v_clash text;
    v_owner text;
    v_found boolean;
    v_drain_until timestamptz;
    v_drain_fp text;
BEGIN
    IF p_fingerprint IS NULL OR p_fingerprint = '' OR p_unique_id IS NULL OR p_unique_id = '' THEN
        RETURN jsonb_build_object('status', 'bad_request');
    END IF;

    IF NOT otp_is_valid(p_otp) THEN
        RETURN jsonb_build_object('status', 'admin_auth_failed');
    END IF;

    -- Guard A: this fingerprint already owns a DIFFERENT active unit -> release first.
    SELECT unique_id INTO v_clash FROM "G_PM_IT_IOT_HAMS_UNITS"
     WHERE device_fingerprint = p_fingerprint AND status = 'active' AND unique_id <> p_unique_id
     LIMIT 1;
    IF v_clash IS NOT NULL THEN
        RETURN jsonb_build_object('status', 'fingerprint_in_use', 'on', v_clash);
    END IF;

    -- Guard B: target unit must exist; never steal one owned by another device.
    SELECT device_fingerprint, true INTO v_owner, v_found FROM "G_PM_IT_IOT_HAMS_UNITS"
     WHERE unique_id = p_unique_id LIMIT 1;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;
    IF v_owner IS NOT NULL AND v_owner <> p_fingerprint THEN
        RETURN jsonb_build_object('status', 'already_bound');
    END IF;

    -- Guard C: unit is mid-drain by ANOTHER device (former owner still flushing).
    -- Refuse the claim until the lease expires; the drainer itself is exempt so
    -- the same phone can re-pair without waiting (see 007_drain_lease.sql).
    SELECT drain_until, drain_fingerprint INTO v_drain_until, v_drain_fp FROM "G_PM_IT_IOT_HAMS_UNITS"
     WHERE unique_id = p_unique_id;
    IF v_drain_until IS NOT NULL AND v_drain_until > now()
       AND v_drain_fp IS DISTINCT FROM p_fingerprint THEN
        RETURN jsonb_build_object('status', 'draining');
    END IF;

    -- Idempotent bind / re-bind to the SAME device. Clears any drain lease.
    UPDATE "G_PM_IT_IOT_HAMS_UNITS" SET claimed = true, device_fingerprint = p_fingerprint,
                     last_seen = now(), updated_at = now(),
                     drain_until = NULL, drain_fingerprint = NULL
     WHERE unique_id = p_unique_id;

    PERFORM consume_otp(p_otp);  -- single-use, only on success
    RETURN jsonb_build_object('status', 'ok', 'unique_id', p_unique_id);
END;
$$;

-- Release a unit -- ONLY the device that owns it (fingerprint-scoped).
CREATE OR REPLACE FUNCTION release_unit(p_unique_id text, p_fingerprint text, p_otp text)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows int;
BEGIN
    IF p_fingerprint IS NULL OR p_fingerprint = '' OR p_unique_id IS NULL OR p_unique_id = '' THEN
        RETURN jsonb_build_object('status', 'bad_request');
    END IF;

    IF NOT otp_is_valid(p_otp) THEN
        RETURN jsonb_build_object('status', 'admin_auth_failed');
    END IF;

    UPDATE "G_PM_IT_IOT_HAMS_UNITS" SET claimed = false, device_fingerprint = NULL, updated_at = now(),
                     drain_until = NULL, drain_fingerprint = NULL
     WHERE unique_id = p_unique_id AND device_fingerprint = p_fingerprint;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows = 0 THEN
        RETURN jsonb_build_object('status', 'not_owner_or_not_found');
    END IF;

    PERFORM consume_otp(p_otp);
    RETURN jsonb_build_object('status', 'ok');
END;
$$;

-- ---------------------------------------------------------------------
-- 007_drain_lease.sql
-- ---------------------------------------------------------------------
-- Drain lease — protects the flush window after an admin frees a unit.
--
-- When a phone learns (via check_binding) that its unit was 'released', it needs
-- a few seconds to flush its pending cuts + the 301 marker to Wialon BEFORE it
-- logs out. During that window no OTHER device may claim the unit, or the old
-- phone's flush would land on the new owner and mix two devices' data.
--
-- Mechanism: check_binding stamps a short TTL lease (drain_until/drain_fingerprint)
-- on every 'released' answer; manual_claim refuses a unit whose lease is
-- unexpired unless the claimer IS the drainer. The lease auto-expires, so no app
-- callback is needed. A successful claim or OTP release clears it.
--
-- Edge (documented): a former-owner phone that is online-for-verify but never
-- gets Wi-Fi to flush will re-stamp the lease on each periodic check, keeping the
-- unit unclaimable until it flushes or the future admin_release override clears it.

ALTER TABLE "G_PM_IT_IOT_HAMS_UNITS" ADD COLUMN IF NOT EXISTS drain_until       TIMESTAMPTZ;
ALTER TABLE "G_PM_IT_IOT_HAMS_UNITS" ADD COLUMN IF NOT EXISTS drain_fingerprint TEXT;

-- ---------------------------------------------------------------------
-- 006_check_binding.sql
-- ---------------------------------------------------------------------
-- Binding revalidation for the app's check-on-push gate. Read-mostly: the app
-- calls this before every push (and at launch) to learn whether its stored
-- unique_id is STILL bound to this device. Lets an admin force-release a unit
-- (see admin_release) without leaving the phone in a split-brain state.
--
-- No OTP: this is an automatic, device-initiated call (no admin present). It is
-- guarded at the n8n layer by the x-hams-key header, exactly like manual-claim.
--
-- Status contract (the app maps these to an action):
--   'bound'      -> unit exists, claimed by THIS fingerprint. App keeps pushing.
--                   Side effect: refreshes last_seen (doubles as a heartbeat).
--   'released'   -> unit exists but is free (claimed=false / no fingerprint).
--                   App MUST self-unprovision and stop pushing.
--   'bound_other'-> unit exists but is owned by a DIFFERENT fingerprint.
--                   App MUST self-unprovision and stop pushing.
--   'not_found'  -> no such unit id in the registry. App should treat this
--                   CONSERVATIVELY (keep last-known-good, do NOT wipe) — it can
--                   be a transient seed/registry gap, not a deliberate release.
--   'bad_request'-> blank input.

CREATE OR REPLACE FUNCTION check_binding(p_unique_id text, p_fingerprint text)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    v_owner   text;
    v_claimed boolean;
BEGIN
    IF p_fingerprint IS NULL OR p_fingerprint = '' OR p_unique_id IS NULL OR p_unique_id = '' THEN
        RETURN jsonb_build_object('status', 'bad_request');
    END IF;

    -- Use plpgsql's built-in FOUND: a manual "true INTO v_found" flag is nulled
    -- out when SELECT INTO matches no row, breaking the not_found check.
    SELECT device_fingerprint, claimed
      INTO v_owner, v_claimed
      FROM "G_PM_IT_IOT_HAMS_UNITS"
     WHERE unique_id = p_unique_id
     LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;

    IF v_claimed AND v_owner = p_fingerprint THEN
        -- Still ours: refresh heartbeat so the admin list shows a live device.
        UPDATE "G_PM_IT_IOT_HAMS_UNITS" SET last_seen = now(), updated_at = now()
         WHERE unique_id = p_unique_id;
        RETURN jsonb_build_object('status', 'bound', 'unique_id', p_unique_id);
    END IF;

    IF v_owner IS NOT NULL AND v_owner <> p_fingerprint THEN
        RETURN jsonb_build_object('status', 'bound_other');
    END IF;

    -- claimed=false or fingerprint cleared -> the unit was released. Stamp a
    -- short drain lease for THIS caller so no other device can claim the unit
    -- while this phone flushes its backlog. Refreshed on each 'released' answer;
    -- auto-expires (see 007_drain_lease.sql).
    UPDATE "G_PM_IT_IOT_HAMS_UNITS"
       SET drain_until = now() + interval '5 minutes',
           drain_fingerprint = p_fingerprint,
           updated_at = now()
     WHERE unique_id = p_unique_id;
    RETURN jsonb_build_object('status', 'released');
END;
$$;

-- ---------------------------------------------------------------------
-- 008_admin_release.sql
-- ---------------------------------------------------------------------
-- Admin force-release (Feature 2). Frees a unit WITHOUT the owning phone or an
-- OTP — the office-side counterpart to the phone's fingerprint+OTP release_unit.
-- Safe only because binding revalidation is in place: a still-alive former owner
-- learns it was freed on its next check_binding and flushes + logs out (status
-- 'released'), or stops without pushing if the unit was already re-claimed
-- ('bound_other'). Intended for dead / lost / reassigned devices.
--
-- Guard this at the n8n layer (login-protected Form or x-hams-key) — the function
-- itself is unguarded by design so the admin console can free any unit.
--
-- Clears the drain lease too: the freed unit starts clean. If the old phone is
-- still alive it re-stamps its own lease when it next reports 'released', which
-- is what protects its flush window.

CREATE OR REPLACE FUNCTION admin_release(p_unique_id text)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows int;
BEGIN
    IF p_unique_id IS NULL OR p_unique_id = '' THEN
        RETURN jsonb_build_object('status', 'bad_request');
    END IF;

    UPDATE "G_PM_IT_IOT_HAMS_UNITS"
       SET claimed = false,
           device_fingerprint = NULL,
           drain_until = NULL,
           drain_fingerprint = NULL,
           updated_at = now()
     WHERE unique_id = p_unique_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 0 THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;

    RETURN jsonb_build_object('status', 'ok', 'unique_id', p_unique_id);
END;
$$;
