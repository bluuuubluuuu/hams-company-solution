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
      FROM units
     WHERE unique_id = p_unique_id
     LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('status', 'not_found');
    END IF;

    IF v_claimed AND v_owner = p_fingerprint THEN
        -- Still ours: refresh heartbeat so the admin list shows a live device.
        UPDATE units SET last_seen = now(), updated_at = now()
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
    UPDATE units
       SET drain_until = now() + interval '5 minutes',
           drain_fingerprint = p_fingerprint,
           updated_at = now()
     WHERE unique_id = p_unique_id;
    RETURN jsonb_build_object('status', 'released');
END;
$$;
