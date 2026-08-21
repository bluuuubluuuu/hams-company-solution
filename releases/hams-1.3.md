# HAMS 1.3 (versionCode 5) — count build

**Spare handset only. Do not distribute to the fleet.** Once a handset takes
versionCode 5 it cannot return to 1.2 without an uninstall, and uninstalling
wipes the pairing and any unsent counts.

| | |
|---|---|
| File | `hams-1.3.apk` |
| versionName / versionCode | `1.3` / `5` |
| Built | 2026-08-21 |
| SHA-256 | `f23e3f049fcdcbadbb6adeabe267d8a5da3c5dce6d10367ed606b087c77f0c30` |
| Signer cert SHA-256 | `98fb0136385382720339d88aec7db90df8e769101f78a9e22097f28617d44f73` |

Contains everything in 1.2 — including the authorship record (`assets/NOTICE.txt`
and the `com.klk.hams.author` manifest meta-data) — plus the count work below.

## What this build addresses

Reports read about 61% of the presses the handsets recorded — 3471 against 5691
on 18 Aug 2026.

The cause is **not** message loss. Raw unit messages from 19 Aug prove Wialon
stores same-second messages: three at `16:06:55`, three at `16:06:57`, two at
`16:06:58`, all retained. Every press ever made is already in Wialon.

The limit is in the notification layer feeding the count report, which fires at
most once per second. Presses sharing a second were reported as one.

## Changes

- **`PRESS_MIN_INTERVAL_MS = 1500`** — minimum gap between two recorded presses
  of the same button, so each press lands in its own second and each triggers a
  notification. Tracked per button, so a `-` correction straight after a `+` is
  never blocked.
- **Refused presses are announced** (`PressFeedback.Refused`). This limit rejects
  presses the worker genuinely made; a silent rejection would recreate the
  invisible loss the change exists to remove.
- **Hold-to-repeat 200 ms → 1500 ms**, matching the limit — a held button must
  not generate presses the limiter would only discard. 200 ms also meant an
  accidental two-second hold registered ten cuts.
- **Unique wire timestamps** (`WireTimestamps`) — a press landing in a second
  already claimed is stored one second later, capped at 300 s of drift. Largely
  redundant while the rate limit holds; it still covers `+` and `-` landing in
  the same second, and keeps the message history orderable. Wialon's view cannot
  order messages that share a timestamp, which is a second quiet cost of
  same-second data.

## The trade-off, stated plainly

A worker tapping faster than one press per 1.5 s now loses those presses **for
real** — on the device as well as in Wialon. Before, the device count stayed
correct and only the report was short. Now they agree, at the lower number.

Watch the field feedback for workers reporting refusals during fast catch-up
counting. If that happens, the alternative costs nothing on the handset: every
press is already in Wialon, so a report built on `work_count` deltas recovers the
true figure — retroactively, for every day already collected.

`PRESS_MIN_INTERVAL_MS` is a single constant if 1.5 s proves to be the wrong
number in either direction.

## Verified

- 284 unit tests pass; lint clean.
- Signature matches the fleet keystore.

Not verified on device: the rate limit, the refusal cue for it, and whether the
notification count now matches the press count. That last one is the test that
matters — tap ten times slowly on the spare, then confirm the report shows ten.
