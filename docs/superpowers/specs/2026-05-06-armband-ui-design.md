# Armband UI Design — HAMS Count Screen

> **Status:** Implemented 2026-05-06, iterated 2026-05-06/07 from on-device feedback. Pending field verification 2026-05-07.
> **Replaces:** broken responsive UI from earlier 2026-05-06 attempt (status pills truncated, count display collapsed to 0 height, +/− buttons at top instead of dominant zone, NEW TASK occupied unused bottom space).
> **Driver:** field-validation feedback. Phone is mounted on the worker's forearm via an armband case during harvesting. Both hands work the FFB; an occasional gloved fingertip from the opposite hand reaches over to tap the screen. Layout must accommodate phone-on-arm in either orientation (top toward elbow OR top toward wrist).

## Revisions (post-implementation feedback)

**2026-05-07 — iteration after first device install:**

- **Status pills simplified.** Removed two-line caption-above-value pattern (caused `BATTERY` → `BATTE` truncation). Pills are now a single Row with a colored dot + value text only. Position carries the metric:
  - left = battery (`93%`), dot color = level (ink ≥20% / amber 10–19% / scarlet <10%)
  - middle = GPS — text always reads `"GPS"`, dot+text color = state (forest=Locked, amber=Stale/Acquiring, slate=NoPermission). Replaces prior `"ON"/"WAIT"/"OFF"` mapping which felt ambiguous.
  - right = task (`#7 · May 6` or `Next #7 · May 7`), dot = forest
- **Count card font reduced** 110sp → 88sp with `softWrap = false` and `overflow = TextOverflow.Visible`. Prior config silently clipped the leading `0` of `0002` → user saw `002` and thought the count was wrong.
- **Today's date preview in TASK pill.** New `CountUiState.todayDate` (refreshed every 1 s in the staleHandler tick). When no active task, the pill shows `Next #N · ${shortDate(todayDate)}` so the date is visible *before* the first press of a new day.
- **Daily rollover wired to UI.** `TaskRepository.rolloverActiveTaskIfStale()` runs once at `CountViewModel.init` before the active-task flow starts. Yesterday's stale active task is finalized (`save_type="auto_rollover"`); today's first + lazy-creates a fresh task. See `CLAUDE.md → Daily rollover` and `HAMS_EVENT_CODE_DICTIONARY.md`.
- **Debug logging added.** `Log.d("HAMS_UI", ...)` in `CountViewModel.onPlus`, `onMinus`, `observeActiveTask`, and the rollover entry. Tail with `adb logcat -s HAMS_UI`.

---

## 1. Constraints (the real ones)

- **Wearable, not handheld.** Phone is locked into an armband case; viewing distance ~30–40 cm at an arm-twist angle. Tap input comes from the opposite hand's gloved fingertip — accuracy is degraded.
- **Both arm-mount orientations supported.** A user may strap the phone with top-toward-elbow OR top-toward-wrist. The same screen must read correctly in either.
- **Single-purpose field instrument.** No mode switching, no nested navigation, no multi-page flow. The worker glances, taps, occasionally undoes, occasionally starts a new task.
- **Daylight + glare.** Cream-on-ink, not pure white. High contrast for primary actions.
- **Glove + jostle.** Big targets, no precision required. No drag, swipe, or hover affordances on critical actions.
- **Battery is precious** (Scenario A). No motion theatre. Only the long-press progress bar animates.

## 2. Orientation strategy

`AndroidManifest.xml` declares `android:screenOrientation="sensorPortrait"` on `MainActivity`. Android allows 0° AND 180° portrait; the OS auto-rotates the rendered surface based on the accelerometer. The user always sees the same layout correctly oriented — top-toward-elbow vs top-toward-wrist becomes invisible at the layout layer.

Landscape support is **deleted** entirely. Armband cases for 6.6" phones are universally portrait, and supporting landscape adds complexity that broke the previous attempt.

## 3. Layout (portrait, ~360 × 780 dp typical)

```
┌─────────────────────────────────────┐  16dp horizontal padding,
│                                     │  windowInsetsPadding(safeDrawing)
│  STATUS STRIP            ~84 dp     │  three pills, equal weight, 10dp gap
│                                     │
│  ──── 16dp gap ────                 │
│                                     │
│  COUNT CARD             weight 1.4  │
│       0 0 0 4                       │
│                                     │
│  ──── 12dp gap ────                 │
│                                     │
│  MEGA + BUTTON          weight 2.0  │
│           +                         │
│       ADD A CUT                     │
│                                     │
│  ──── 12dp gap ────                 │
│                                     │
│  UTILITY ROW             ~96 dp     │  − (weight 1) | NEW TASK (weight 2)
│                                     │
└─────────────────────────────────────┘
```

**Critical invariant: no element competes with the count or the + button for height.** Status strip and utility row use `wrapContentHeight` with bounded min-heights (`heightIn(min = 84.dp)` / `heightIn(min = 96.dp)`). They take what they need, never weighted. All leftover space flows to count card (1.4f) and + button (2.0f) — the + button is always the largest single element on screen.

## 4. Status strip

Three pills, `Row` with `Arrangement.spacedBy(10.dp)`. Each pill is `Modifier.weight(1f)` (battery, gps) or `Modifier.weight(1.3f)` (task, slightly wider for the date suffix).

Each pill is a `Surface` with `RoundedCornerShape(12.dp)`, `1.dp` hairline border, `12.dp / 10.dp` internal padding. Inside each pill is a vertical `Column`:

```
● BATTERY        ← row: 8dp dot + caption (labelMedium, tracked)
84%              ← value (titleMedium, semibold, single line)
```

```
● TASK
#6 · May 6       ← value combines seq and short date on one line
```

Date short format: `task_date` (`YYYY-MM-DD`) is parsed and reformatted to `"May 6"` (`MMM d` locale-aware). Empty `task_date` (no active task) → pill shows `Next #${pendingTaskSeq}` only, no date.

GPS pill value mapping:
- `Locked` → `"LOCKED"` in forest green
- `Stale` → `"RE-ACQUIRING"` in amber
- `Acquiring` → `"ACQUIRING"` in amber
- `NoPermission` → `"OFF"` in slate

Battery pill color:
- `< 10` → scarlet
- `< 20` → amber
- otherwise → ink

The pill's leading dot inherits the same accent color as its value. No separate pill background tint — the colored value + dot carries the meaning.

## 5. Count card

`Surface(shape = RoundedCornerShape(20.dp), color = surface, border = hairline)`. Single Text inside, vertically centered:

- `text = "%04d".format(count)`
- `fontFamily = FontFamily.Monospace`
- `fontWeight = FontWeight.Black`
- `fontSize = 140.sp` — **fixed**, no auto-shrink. The card has weight 1.4f, plenty of room.
- `color = FieldInk`
- `textAlign = Center`, `maxLines = 1`

When `count >= 9999`, a small scarlet caption appears below: `"Maximum reached — start new task"` (`bodyMedium`).

## 6. Mega + button

Not a Material `Button` — a custom `Surface` so we own the inner layout completely.

```
Surface(
    onClick = vm::onPlus,
    enabled = state.canIncrement,
    color = if (canIncrement) FieldForest else FieldHairline,
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier.fillMaxWidth().weight(2f)
) {
    Column(verticalArrangement = Center, horizontalAlignment = CenterHorizontally) {
        Text("+", mono Black 120sp, color = forestOn or slate)
        Spacer(12.dp)
        Text(captionFor(state), labelLarge tracked uppercase)
    }
}
```

`captionFor`:
- `canIncrement` → `"ADD A CUT"`
- `gpsLockState != Locked` → `"WAITING FOR GPS LOCK"` (worker knows why it won't respond)
- `count >= MAX_COUNT_PER_TASK` → `"MAX REACHED · NEW TASK"`

Disabled state: hairline-cream background, slate text. Press feedback comes from `Surface(onClick = ...)`'s built-in ripple.

## 7. Utility row

`Row(spacedBy = 10.dp)`, both children `heightIn(min = 96.dp)`:

- **− button** (`weight(1f)`): earth-brown surface, "−" mono 56sp + caption "UNDO" labelLarge. Disabled (slate / hairline) when `!canDecrement`.
- **NEW TASK** (`weight(2f)`): cream surface with hairline border. Same `pointerInput` long-press machinery as today (5-second hold → confirm dialog). Caption changes:
  - `count == 0` → `"NEW TASK"` (slate, disabled-looking) — Toast on tap as today
  - `count > 0` → `"HOLD · NEW TASK"` in forest

When `state.newTaskProgress > 0f`, the `LinearProgressIndicator` renders **above** the utility row at full width, 4dp tall, forest fill on hairline track.

## 8. Typography (fixed sizes, no auto-shrink)

| Element                          | Family       | Size  | Weight |
|----------------------------------|--------------|-------|--------|
| Count digit                      | Monospace    | 140sp | Black  |
| + symbol                         | Monospace    | 120sp | Black  |
| − symbol                         | Monospace    | 56sp  | Black  |
| Pill value                       | SansSerif    | 18sp  | SemiBold |
| Pill caption (uppercase tracked) | SansSerif    | 11sp  | Medium |
| Button caption (uppercase track) | SansSerif    | 16sp  | SemiBold |
| Body / dialog                    | SansSerif    | 16sp  | Normal |

## 9. State machine — unchanged

`LocationGateState` machine (CheckingPermission → RequestingPermission → CheckingLocationServices → Ready / PermissionDenied / LocationServicesOff) is preserved verbatim. The new layout only replaces `CountingContent`'s body. The same `vm.onPlus()`, `vm.onMinus()`, `vm.onNewTaskPressStart()`, `vm.onNewTaskPressCancel()`, `vm.onNewTaskConfirmed()`, `vm.onNewTaskDismissed()`, `vm.onGatePassed()` calls are wired identically.

## 10. Files affected

| File | Change |
|------|--------|
| `app/src/main/AndroidManifest.xml` | Add `android:screenOrientation="sensorPortrait"` to `<activity android:name=".MainActivity">` |
| `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt` | Rewrite `CountingContent`. Delete `LandscapeLayout`, `PortraitLayout`, `BoxWithConstraints`-based orientation switch, auto-shrink count sizing logic. Add date short-format helper. |
| `app/src/main/java/com/klk/hams/ui/count/CountUiState.kt` | (unchanged — already exposes `taskDate: String`) |
| `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt` | (unchanged — already populates `taskDate` from active task) |
| `app/src/main/java/com/klk/hams/ui/theme/Theme.kt` / `Color.kt` / `Type.kt` | (unchanged — palette and typography stay) |

## 11. Out of scope

- Landscape layout (deleted)
- Dark mode (single light cream theme)
- Dynamic color (would override the forest accent)
- Settings screen, history list, push status panel — separate Phase 3 work
- Animation beyond the long-press progress bar
- Custom font loading (system Mono + SansSerif only)

## 12. Acceptance criteria

- App launches in portrait; status pills are single-row, never truncate the value, never wrap the caption.
- The count digit is the visible "0000" element above the + button on first launch (no count card collapse).
- + button occupies the visually largest single area on screen (~50% of vertical space).
- Mounting the phone in 180° rotated portrait causes Android to flip the UI; the layout still presents identically to the worker.
- − button visible and tappable when `count > 0`; greyed-out when `count == 0`.
- "HOLD · NEW TASK" caption is visible at all times when `count > 0`; the long-press progress bar appears above it during a hold.
- All status pill text fits on one line at 360 dp width without truncation: "BATTERY 100%", "GPS LOCKED", "TASK #99 · May 31".
