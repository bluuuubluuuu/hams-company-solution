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

ALTER TABLE units ADD COLUMN IF NOT EXISTS drain_until       TIMESTAMPTZ;
ALTER TABLE units ADD COLUMN IF NOT EXISTS drain_fingerprint TEXT;
