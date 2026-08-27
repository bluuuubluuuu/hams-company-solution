<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# Testing Record

**Purpose:** Brief record of the HAMS behaviour already tested. It records prior verification; it is not a live test run or a substitute for release regression testing.

## Recorded verification

| Area | Test coverage |
|---|---|
| App launch | GPS permission and location-service gate block counting when unavailable. |
| Counting | `+`, `−`, scaled count, displayed/net count, and maximum-count handling. |
| Tasks | First-cut task creation, new-task hold, save/rollover, and app-removal recovery. |
| Offline | Events persist locally before any network delivery. |
| GPS | Fresh-location requirement; stale or absent GPS disables count actions. |
| IPS frame | Login, V6 16-field frame, DDMM.MMMM coordinate conversion, and named parameters. |
| Event policy | `179`, productive `180`, and `35` use the task delivery path; local-only events do not. |
| Delivery | Batch delivery, acknowledgements, retry/backoff, manual delivery, and automatic network-triggered delivery. |
| Push safety | Manual and background delivery coordination prevents duplicate work. |
| Device lifecycle | OTP pairing, bad-OTP rejection, release, re-pairing, and office admin release. |
| Binding checks | `bound`, `released`, `bound_other`, and conservative `not_found` outcomes. |
| Release safety | Pending cuts deliver before release when possible; failed delivery strands rows safely. |
| Diagnostics | Boot/shutdown, GPS transition, motion, screen, and power telemetry path. |
| Wialon | Message receipt, named parameters, and unit filter/sensor configuration. |
| Backend contract | n8n claim/release/verify flow and PostgreSQL routine status responses. |

## Verified 19-20 August 2026

| Area | Result |
|---|---|
| Press feedback (1.2) | Distinct `+`/`-` tones and vibration confirmed on a handset. |
| In-place upgrade | 1.2 to 1.3 over an existing install kept pairing, fingerprint, and unsent rows. |
| Wialon message retention | Raw unit messages show same-timestamp messages retained - three at `16:06:55`, three at `16:06:57`. No task message lost in transport. |
| Automatic seeding | End-to-end: a code request for a unit absent from the registry seeded it before the OTP was issued. |
| Webhook path stability | The production OTP URL after republishing matches `OTP_REQUEST_URL` compiled into the deployed APKs. |

Not verified on a handset: the 1.3 press rate limit, its refusal cue, and whether the notification-based count now matches the number of presses. That last one is the acceptance test for 1.3.

## Verified 21 August 2026 — authorship record and version footer

Both builds were rebuilt to carry the authorship record and the count-screen version footer. Checks were run against the APK files, not the build log:

| Check | 1.2 | 1.3 |
|---|---|---|
| `versionCode` / `versionName` | 3 / `1.2` | 5 / `1.3` |
| Signer fingerprint matches the fleet keystore | yes | yes |
| `assets/NOTICE.txt` present | yes | yes |
| `com.klk.hams.author` meta-data present | yes | yes |
| Unit tests, lint | pass | pass |

1.3 was installed over an existing 1.3 on the spare handset; `firstInstallTime` was unchanged, confirming an in-place update that preserved pairing and unsent rows. The installed APK was pulled back and its SHA-256 matched the release file byte for byte.

**Not verified on a handset:**

- **The footer's final wording has never been seen on a screen.** An earlier build showing `HAMS 1.3 (5)` was confirmed visually; the shipped name-only form (`HAMS 1.2` / `HAMS 1.3`) was installed but not observed.
- **1.2 has not run on any device.** Every attempt was blocked by the Android downgrade rule, since the only available handset already held versionCode 5. 1.2 is verified as a file only — its footer, layout, and behaviour are unproven on hardware. This matters most of the three gaps here, because 1.2 is the build intended for twelve handsets.
- **Layout at other screen sizes.** The footer takes no fixed height and the surrounding column absorbs the change through `ActionRow`'s weight, but this was only ever exercised at 720x1570 / density 320.

## Known verification gap

The production Wialon report-template result is still not verified end to end.

The cause of the shortfall is now established - the notification layer triggers at most once per second, so presses sharing a second were counted once - but neither remedy has been confirmed against a full day of field data. Treat both as outstanding:

- **1.3 rate limit** - does the notification count now equal the presses made?
- **`work_count` delta report** - does it reproduce the true figure, including across task resets and `-` corrections? The `delta <= -2 means new task` rule is inferred and unproven.

See [Functional requirements](requirements/FR_APP_REQUIREMENTS.md), [Non-functional requirements](requirements/NFR_APP_REQUIREMENTS.md), and the [Operations runbook](../operations/OPERATIONS_RUNBOOK.md).
