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
