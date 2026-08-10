-- =====================================================================
-- HAMS provisioning schema - complete setup script
-- =====================================================================
-- Run ONCE on a fresh, empty database:
--     psql "$PROV_DB_URL" -f hams_setup.sql
--
-- This script CREATES EVERYTHING outright: every column is declared in its
-- CREATE TABLE, not bolted on afterwards. It is the single source of truth
-- for the schema. sql/history/ records how the schema was arrived at during
-- development and is NOT meant to be run - read it only if you need the
-- reasoning behind a design decision.
--
-- Idempotent: safe to re-run. Tables use CREATE TABLE IF NOT EXISTS,
-- functions use CREATE OR REPLACE. Re-running never resets a live
-- device pairing.
--
-- NAMING (SOP): tables and indexes are QUOTED UPPERCASE with the
-- G_PM_IT_IOT_HAMS_ prefix. Every reference must be quoted:
--     SELECT * FROM "G_PM_IT_IOT_HAMS_UNITS";   -- correct
--     SELECT * FROM G_PM_IT_IOT_HAMS_UNITS;     -- ERROR (folds to lowercase)
-- Functions and columns remain unquoted lowercase. The function names are
-- called BY NAME from the n8n workflows - renaming one breaks a workflow.
--
-- Object count after a clean run: 2 tables, 11 + 4 columns, 2 named indexes,
-- 8 functions.
-- =====================================================================


-- =====================================================================
-- SECTION 1 - TABLES
-- =====================================================================

-- ---------------------------------------------------------------------
-- The equipment register. One row per Wialon unit / handset.
-- ---------------------------------------------------------------------
-- The authoritative free/claimed state lives HERE, not in Wialon. Rows are
-- created by the n8n seeding flow reading the company's Wialon account; the
-- per-device claim reads and writes this table only.
--
-- This table holds CURRENT STATE. Columns are overwritten in place; it is not
-- a history log.

CREATE TABLE IF NOT EXISTS "G_PM_IT_IOT_HAMS_UNITS" (
    -- Identity ---------------------------------------------------------
    unique_id          TEXT PRIMARY KEY,               -- Wialon unit IPS unique id (e.g. OC003_H001).
                                                       -- The phone sends this in its login frame; it is
                                                       -- what routes harvest data to the right unit.
    name               TEXT,                           -- human-readable label copied from Wialon
                                                       -- (e.g. 003_G01C01_Mobile1). Carries NO logic -
                                                       -- safe to rename in Wialon at any time.

    -- Ownership --------------------------------------------------------
    claimed            BOOLEAN NOT NULL DEFAULT false, -- false = free for pairing
    device_fingerprint TEXT UNIQUE,                    -- phone ANDROID_ID. Proof of ownership: only the
                                                       -- phone presenting this exact value may push as
                                                       -- this unit or release it. NULL when free.
                                                       -- UNIQUE stops one phone claiming two units.
                                                       -- PostgreSQL permits many NULLs in a UNIQUE
                                                       -- column; SQL Server does NOT - it would need a
                                                       -- filtered index. See SCHEMA.md.
    status             TEXT NOT NULL DEFAULT 'active', -- 'active' in service | 'retired' withdrawn.
                                                       -- Administrative; no routine sets 'retired'.

    -- Telemetry --------------------------------------------------------
    last_seen          TIMESTAMPTZ,                    -- last contact from the phone: set at pairing,
                                                       -- refreshed on every binding re-check (~15 min).
    app_version        TEXT,                           -- APK version this handset last reported, so the
                                                       -- office can answer "is every phone on the
                                                       -- current build?". Reported by check_binding on a
                                                       -- call that already happens - no extra endpoint.

    -- Audit ------------------------------------------------------------
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),  -- maintained BY EACH ROUTINE, not by a
                                                            -- trigger. Do not add a trigger: it would
                                                            -- double-write against the routines.

    -- Drain lease ------------------------------------------------------
    -- When a unit is freed while its phone is still in the field, that phone may
    -- hold unsent cuts. It needs a few minutes to flush them BEFORE logging out.
    -- During that window no other phone may claim the unit, or the old phone's
    -- backlog lands on the new owner and two workers' output merges.
    --
    -- check_binding stamps this lease on every 'released' answer; manual_claim
    -- refuses a unit whose lease is unexpired unless the claimer IS the drainer.
    -- Auto-expires, so no background job is needed. Cleared by the next
    -- successful claim or release.
    --
    -- This is LIVE GUARD STATE, not history: it must clear, so it can never be
    -- used to record who previously held a unit.
    drain_until        TIMESTAMPTZ,                    -- lease expiry; NULL when no lease is held
    drain_fingerprint  TEXT                            -- which phone holds the lease (exempt from it)
);

-- ---------------------------------------------------------------------
-- Admin OTP store. Short-lived, single-use codes.
-- ---------------------------------------------------------------------
-- An administrator generates a code to authorise a pairing or unpairing. The
-- code is validated first and CONSUMED ONLY IF THE ACTION SUCCEEDS, so a
-- rejected attempt never burns a code.
--
-- Self-purging: issue_otp() deletes expired rows on every call. No cleanup job
-- is needed and none should be added. Expected size is under 10 rows.
--
-- This table does NOT record which administrator issued a code, from where, or
-- which unit it was spent on. There is no attribution by design.

CREATE TABLE IF NOT EXISTS "G_PM_IT_IOT_HAMS_ADMIN_OTP" (
    code        TEXT PRIMARY KEY,                   -- the 6-digit code, stored in PLAINTEXT
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(), -- issue time; audit only
    expires_at  TIMESTAMPTZ NOT NULL,               -- hard expiry, default 10 min after issue.
                                                    -- Past this the code is invalid regardless of used_at.
    used_at     TIMESTAMPTZ                         -- non-NULL = spent. Single-use is enforced by
                                                    -- checking used_at IS NULL.
);


-- =====================================================================
-- SECTION 2 - INDEXES
-- =====================================================================
-- (The 3 primary-key indexes and the device_fingerprint UNIQUE index are
--  created implicitly by the constraints above.)

-- Partial index over the free, in-service units only.
--
-- CURRENTLY UNUSED. No routine queries `WHERE claimed = false AND status =
-- 'active'`: pairing is office-manual, so manual_claim() receives the exact
-- unit id and looks it up by primary key. Nothing asks the database to pick
-- a free unit.
--
-- Retained because it is near-free to maintain at this row count (one row per
-- handset), and it is what a future "auto-assign the next free unit" flow or a
-- "how many units are free?" dashboard would ride on. Pairing does NOT depend
-- on it.
CREATE INDEX IF NOT EXISTS "G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"
    ON "G_PM_IT_IOT_HAMS_UNITS" (unique_id)
    WHERE claimed = false AND status = 'active';


-- =====================================================================
-- SECTION 3 - OTP FUNCTIONS
-- =====================================================================
-- Guard/OTP logic lives in plpgsql (atomic, testable) so the n8n workflows
-- stay thin: each workflow calls one function and maps its returned status
-- to an HTTP code.

-- Issue a new OTP (6-digit). Purges expired rows first - this keeps the table
-- small and avoids PK collisions with stale codes. Returns the plaintext code.
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

-- Consume (single-use): atomically mark used; true only if it was still valid
-- at that instant.
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


-- =====================================================================
-- SECTION 4 - PROVISIONING FUNCTIONS
-- =====================================================================
-- Each returns jsonb {status, ...} that n8n maps to an HTTP code. The status
-- strings are a PUBLISHED INTERFACE - the Android app branches on them, so
-- renaming one requires rebuilding and reinstalling the app on every handset.

-- ---------------------------------------------------------------------
-- seed_unit - seeding UPSERT from Wialon
-- ---------------------------------------------------------------------
-- Adds new unit ids as free; for an existing id it ONLY refreshes the name. It
-- deliberately does NOT touch claimed / device_fingerprint / status, so
-- re-running the seeding flow can never reset a live device assignment.
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
-- manual_claim - bind a specific unit to a handset
-- ---------------------------------------------------------------------
-- Admin supplies the unit id; the phone supplies its fingerprint; the OTP
-- authorises it. OTP is validated first and consumed ONLY on success, so a
-- failed guard never burns a code.
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
    -- the same phone can re-pair without waiting.
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

-- ---------------------------------------------------------------------
-- release_unit - worker unpairs, with an OTP
-- ---------------------------------------------------------------------
-- ONLY the device that owns the unit may release it, proven by fingerprint.
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
-- check_binding - the phone's periodic "am I still bound?"
-- ---------------------------------------------------------------------
-- Called at launch, before every push, and every ~15 min. Read-mostly. Lets an
-- admin force-release a unit without leaving the phone in a split-brain state.
--
-- No OTP: this is an automatic, device-initiated call with no admin present. It
-- is guarded at the n8n layer by the x-hams-key header, exactly like the others.
--
-- Status contract (the app maps each to an action):
--   'bound'       -> claimed by THIS fingerprint. Keep pushing.
--                    Side effects: refreshes last_seen, records app_version.
--   'released'    -> unit exists but is free. App MUST flush, then unprovision.
--                    Side effect: stamps a 5-minute drain lease for this caller.
--   'bound_other' -> owned by a DIFFERENT fingerprint. App MUST unprovision and
--                    must NOT push - its work would land on someone else's unit.
--   'not_found'   -> no such unit id. App treats this CONSERVATIVELY (keep
--                    last-known-good, do NOT wipe): it can be a transient
--                    seed/registry gap rather than a deliberate release.
--   'bad_request' -> blank input.
--
-- p_app_version is OPTIONAL (defaults to NULL) so an older APK that does not
-- send it still works, and COALESCE below means such a phone cannot blank out a
-- previously recorded version.
--
-- NOTE if upgrading a database built before app_version existed: adding a
-- parameter creates an OVERLOAD in PostgreSQL, not a replacement, and a 2-arg
-- call would then match both signatures ("function check_binding(text, text)
-- is not unique"). Drop the old one first:
--     DROP FUNCTION IF EXISTS check_binding(text, text);
CREATE OR REPLACE FUNCTION check_binding(
    p_unique_id   text,
    p_fingerprint text,
    p_app_version text DEFAULT NULL
)
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
        -- Still ours: refresh the heartbeat so the admin list shows a live
        -- device, and record the APK version it is running.
        UPDATE "G_PM_IT_IOT_HAMS_UNITS"
           SET last_seen   = now(),
               updated_at  = now(),
               app_version = COALESCE(p_app_version, app_version)
         WHERE unique_id = p_unique_id;
        RETURN jsonb_build_object('status', 'bound', 'unique_id', p_unique_id);
    END IF;

    IF v_owner IS NOT NULL AND v_owner <> p_fingerprint THEN
        RETURN jsonb_build_object('status', 'bound_other');
    END IF;

    -- claimed=false or fingerprint cleared -> the unit was released. Stamp a
    -- short drain lease for THIS caller so no other device can claim the unit
    -- while this phone flushes its backlog. Refreshed on each 'released'
    -- answer; auto-expires.
    UPDATE "G_PM_IT_IOT_HAMS_UNITS"
       SET drain_until = now() + interval '5 minutes',
           drain_fingerprint = p_fingerprint,
           updated_at = now()
     WHERE unique_id = p_unique_id;
    RETURN jsonb_build_object('status', 'released');
END;
$$;

-- ---------------------------------------------------------------------
-- admin_release - office force-free, no phone and no OTP
-- ---------------------------------------------------------------------
-- The office-side counterpart to the phone's fingerprint+OTP release_unit.
-- Intended for dead / lost / reassigned handsets.
--
-- Safe only because binding revalidation is in place: a still-alive former
-- owner learns it was freed on its next check_binding and flushes + logs out
-- ('released'), or stops without pushing if the unit was already re-claimed
-- ('bound_other').
--
-- Guard this at the n8n layer (login-protected Form or x-hams-key). The
-- function itself is unguarded by design so the admin console can free any unit.
--
-- Clears the drain lease too, so the freed unit starts clean. If the old phone
-- is still alive it re-stamps its own lease when it next reports 'released',
-- which is what protects its flush window.
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
