<!-- DRAFT — production-content review required. -->
# Device Pairing, Release, and Replacement

This checklist describes the behaviour implemented by the Android app. It does not define the company’s live Wialon unit settings, n8n deployment, database procedures, or support roles; those must be confirmed by the company platform administrator.

## Preconditions

Before pairing a handset, the office administrator needs:

- The approved HAMS app build installed on the handset.
- Android location services enabled and the app’s location permission granted.
- The Wialon unit identifier that the company has made available for HAMS.
- A valid supervisor OTP, issued through the company’s approved process.
- Working network access to the configured provisioning endpoint.

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
