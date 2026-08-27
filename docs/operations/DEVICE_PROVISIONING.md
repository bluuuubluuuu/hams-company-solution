<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# Device Pairing, Release, and Replacement

This checklist describes the behaviour implemented by the Android app. It does not define the company’s live Wialon unit settings, n8n deployment, database procedures, or support roles; those must be confirmed by the company platform administrator.

## Preconditions

Before pairing a handset, the office administrator needs:

- The approved HAMS app build installed on the handset.
- Android location services enabled and the app’s location permission granted.
- The Wialon unit identifier that the company has made available for HAMS.
- A valid supervisor OTP, issued through the company’s approved process.
- Working network access to the configured provisioning endpoint.

### Registry seeding

A unit must exist in the registry before it can be claimed; `manual_claim` answers `not_owner_or_not_found` otherwise.

Since 20 August 2026 this is automatic. When a handset requests a supervisor code, it sends the unit id it is about to pair with, and the Device OTP workflow seeds that unit before issuing the code:

1. The unit id is checked against `^OC\d{3}_H_[A-Za-z0-9]+$`. A missing or malformed id skips seeding; the OTP is still issued.
2. The registry is checked first. A unit already present skips Wialon entirely.
3. Otherwise Wialon is searched for that one unit and `seed_unit` inserts it.
4. Only then is the OTP issued and emailed, so the row always exists before anyone can type the code.

Seeding is best-effort by design: if Wialon is unreachable the OTP is still issued, and the failure appears **only** in the n8n execution log. A unit that is licensed in Wialon but absent from the registry after a code request means seeding failed — check that execution before assuming the unit id is wrong.

Manual seeding remains available through the Seed workflow’s manual trigger, and is still the route for bulk onboarding or for a unit nobody has requested a code for.

The current source supports Android API 33–35 and uses `Settings.Secure.ANDROID_ID` as the device fingerprint. Do not move a production signing key or rebuild a release under a different signing identity without planning re-pairing.

## Pair a new device

1. Open the app. An unpaired device displays the pairing screen.
2. Enter the unit identifier and supervisor OTP.
3. Submit the claim request.
4. Confirm the app reports a successful binding and opens the count screen.
5. Record the handset asset identifier, app version, unit identifier, pairing date, and responsible administrator in the company’s operational register.
6. Make a controlled test only under the company-approved Wialon test/production procedure, then confirm the intended downstream result with the platform administrator.

The app emits a `303` device-bound diagnostic record as part of successful binding. Its arrival at an external platform depends on working network and the configured backend/telemetry route.

## Verify an already paired device

The app checks its binding at launch, before delivery, and on a periodic background schedule. Possible source-level outcomes are:

| Result | App response |
|---|---|
| Bound to this device | Continues normal operation. |
| Released | Attempts safe release handling, then returns to an unpaired state. |
| Bound to another device | Stops delivery and returns to an unpaired state. |
| Registry record not found | Keeps the last-known-good local state conservatively; investigate with the company administrator. |

If a device unexpectedly returns to pairing, stop further counting/delivery attempts and ask the company administrator to inspect the current unit assignment before re-pairing.

## Release or replace a device

Use the app’s administrator release control with a valid OTP. The app’s safety sequence is:

1. Verify that this handset still owns the unit.
2. Attempt a bounded delivery of pending work under that existing unit (current code budget: 15 seconds).
3. Call the configured release endpoint.
4. Mark any still-pending task-event rows as stranded (`pushed = 2`), preventing delivery under a later unit assignment.

On a clean release the app records code `304` (device unbound). If pending work is stranded, it records code `302` and includes `lost_cuts` when available. These are diagnostics, not proof that a remote system received a message.

For a replacement handset, complete the release first, confirm the company registry is ready, then pair the new handset. Do not pair a second device to the same unit while the old phone is still being investigated.

## Company platform confirmation required

Before treating a unit as production-ready, the company platform administrator must confirm outside this repository:

- The assigned unit is permitted to accept the app’s IPS identity and frame format.
- The intended sensors, reports, notifications, and downstream workflow recognise the current parameters/codes.
- The provisioning registry has the unit available for claim.
- The responsible support contact and incident process are known.

Use [Android application specification](../reference/ANDROID_APP_SPEC.md) for source-derived behaviour and [Event-code dictionary](../reference/EVENT_CODE_DICTIONARY.md) for the active code mapping.
