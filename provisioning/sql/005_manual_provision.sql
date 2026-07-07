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
    SELECT unique_id INTO v_clash FROM units
     WHERE device_fingerprint = p_fingerprint AND status = 'active' AND unique_id <> p_unique_id
     LIMIT 1;
    IF v_clash IS NOT NULL THEN
        RETURN jsonb_build_object('status', 'fingerprint_in_use', 'on', v_clash);
    END IF;

    -- Guard B: target unit must exist; never steal one owned by another device.
    SELECT device_fingerprint, true INTO v_owner, v_found FROM units
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
    SELECT drain_until, drain_fingerprint INTO v_drain_until, v_drain_fp FROM units
     WHERE unique_id = p_unique_id;
    IF v_drain_until IS NOT NULL AND v_drain_until > now()
       AND v_drain_fp IS DISTINCT FROM p_fingerprint THEN
        RETURN jsonb_build_object('status', 'draining');
    END IF;

    -- Idempotent bind / re-bind to the SAME device. Clears any drain lease.
    UPDATE units SET claimed = true, device_fingerprint = p_fingerprint,
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

    UPDATE units SET claimed = false, device_fingerprint = NULL, updated_at = now(),
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
