# HAMS 1.2 (versionCode 3) — feedback build

Distribution build for the 12 production handsets. Adds audible + haptic press
feedback; no change to counting logic, GPS gating, or the Wialon wire format.

| | |
|---|---|
| File | `hams-1.2.apk` |
| Application ID | `com.klk.hams` |
| versionName / versionCode | `1.2` / `3` |
| Built | 2026-08-21 |
| SHA-256 | `2e6a2afd140d12845147df46a5f3f00e7b8e7e2c33eb628dab678c687c8a5cd9` |
| Signer cert SHA-256 | `98fb0136385382720339d88aec7db90df8e769101f78a9e22097f28617d44f73` |
| Signer DN | `CN=HAMS Task Recorder, OU=IT, O=KLK, L=Ipoh, ST=Perak, C=MY` |
| Signature scheme | v2 |
| min / target SDK | 33 / 35 |

The signer fingerprint matches `keys/hams-release.jks`, i.e. the key the fleet
already trusts. Installs in place over 1.1: pairing, `device_fingerprint` and
unsent event rows are all preserved.

## Version footer

The bottom of the count screen reads `HAMS 1.2`, so a supervisor can identify a
handset's build without adb. `AppConfig.UI_VERSION_FOOTER` is the single edit
point; blank hides the line. Name only — versionCode stays in `APP_VERSION` for
the registry.

## Authorship record

Every build carries `Built by Woo Ying Hui, 2026` in two places, neither of
which renders on screen:

- `assets/NOTICE.txt` — `unzip -p hams-1.2.apk assets/NOTICE.txt`
- manifest `<meta-data android:name="com.klk.hams.author">` — visible to
  `aapt dump xmltree` or APK Analyzer without unpacking

## What changed from 1.1

- Three bundled cues in `res/raw` — `press_plus` (1568 Hz blip), `press_minus`
  (784 Hz, an octave down), `press_refused` (210 Hz double buzz).
- Feedback moved from touch time to **after** the row is stored, so a cue can
  only ever mean what it says. `CountViewModel` reports the outcome through a
  `SharedFlow<PressFeedback>`; `FieldFeedback` plays it.
- The +/- buttons no longer swallow a press when GPS is stale. The press reaches
  the ViewModel, is refused, and announces the refusal. GPS gating itself is
  unchanged — no fresh snapshot still means no count.
- Haptic raised from a 35 ms pulse at default amplitude to 60 ms at full
  amplitude where the device supports it. 35 ms sat under the perceptible
  threshold on low-cost motors, which is what the "no vibration" field reports
  were actually describing.

## Verified

- `assembleDebug`, `testDebugUnitTest`, `lintDebug` all pass.
- Signature verified with `apksigner` against the keystore fingerprint above.
- Sound and vibration confirmed on device.

Not covered by automated tests: the feedback path needs `AndroidViewModel`, and
this project has no Robolectric. Behaviour was confirmed by hand instead.

## Source state

Built from tag `v1.2` plus the authorship record described above, which is the
only difference from the tagged tree. Reproducible.

## Next

`1.3` / versionCode 5 carries the count work and is for the spare handset only —
see `hams-1.3.md`.
