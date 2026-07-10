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
