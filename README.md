# HAMS Task Recorder — Documentation Hub

Start here. This page is the **menu**: what each document is for, what you must supply, and the
order to build the system in. Every detailed instruction lives in a linked sub-document — think of
this as the index of a wiki.

**What HAMS is:** an Android app that replaces a hardware GPS tracker for oil-palm harvesters.
Workers press **+** per FFB (Fresh Fruit Bunch) cut; each press records GPS + battery + timestamp
offline (SQLite). On validated Wi-Fi the app batch-pushes the cuts to the **Wialon** cloud (Wialon
IPS protocol). A small **n8n + Postgres** backend handles only *identity* — binding each phone to a
Wialon unit id via an office admin + a one-time password (OTP). It never touches cut data.

```
[Phone: HAMS app] --pair (unit id + OTP)--> [n8n webhook] --> [Postgres: units table]
[Phone: HAMS app] --cut data (IPS #D#)-----------------------> [Wialon cloud]
```

<img src="docs/image_guideline/app/app-01-count-screen.jpeg" width="260" alt="HAMS count screen">

> Screenshots referenced throughout the docs live in [`docs/image_guideline/`](docs/image_guideline)
> (`app/` = phone UI, `n8n/` = backend workflows).

---

## 📖 Documentation menu

Read top-to-bottom the first time; after that, jump to what you need.

### Setup & operation
| Document | Purpose | Read when |
|---|---|---|
| **[SETUP.md](SETUP.md)** | End-to-end build walkthrough: from a fresh clone to a paired phone pushing real cuts. Prerequisite-gated, step by step. | You are setting the whole thing up. **Main guide.** |
| **[provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)** | How to build the n8n + Postgres admin backend — fast (import ready workflows) or from scratch (node-by-node), plus a gotchas list and curl test matrix. | You are standing up the backend (called from SETUP §5). |
| **[provisioning/README.md](provisioning/README.md)** | Index of the `provisioning/` folder (SQL, workflow JSONs) and what each file is. | You want to know what's in the backend folder. |
| **[TEST_CASES.md](TEST_CASES.md)** | Verification checklist — automated tests + backend + app acceptance cases with expected results. | You want to confirm a build works end to end. |
| **[LOCAL-RUN.md](LOCAL-RUN.md)** | 60-second "run it all on one PC" checklist that links to the full guides. | You just want the quick localhost bring-up order. |

### Reference (look up, don't read cover-to-cover)
| Document | Purpose | Read when |
|---|---|---|
| **[CONFIG_REFERENCE.md](CONFIG_REFERENCE.md)** | One-page cheat sheet: every config key, the shared-platform values, the webhook status→HTTP contract, and where each secret lives. | You need a value or a contract fast. |
| **[COMPANY_HANDOFF.md](COMPANY_HANDOFF.md)** | Project overview: what's included, how pairing works, scope. | You want the big picture, not steps. |
| **[CONTEXT.md](CONTEXT.md)** | The Wialon side + the IPS protocol (frame format, coordinate conversion, unit admin). | You are integrating with Wialon or debugging the wire format. |
| **[docs/HAMS_APP_REQUIREMENTS.md](docs/HAMS_APP_REQUIREMENTS.md)** | Functional & non-functional requirements. | You are reviewing scope/behaviour. |
| **[docs/HAMS_EVENT_CODE_DICTIONARY.md](docs/HAMS_EVENT_CODE_DICTIONARY.md)** | The canonical event-code list and push rules. | Any question about event semantics. |
| **[docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md](docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md)** | Per-unit Wialon configuration a unit needs before it will store data (filters, sensors). | You are creating/configuring Wialon units. |

> `CLAUDE.md`, `docs/checkpoints/`, `docs/superpowers/`, and `plans/` are development history and
> build rules for contributors — not part of the setup path.

---

## 🔑 Credentials & platform access

There are two kinds of values. **Do not hardcode anything in source** — you supply values through
`local.properties` (copied from `local.properties.example`) and the n8n credential fields. The app
reads them at build time; you never edit `.kt` files.

### You must provide your own — 🔴 required
| What | You obtain it from | Used in |
|---|---|---|
| **Wialon account + unit(s)** | Your Wialon provider/reseller | receives the cut data |
| **`WIALON_TOKEN`** (72 chars) | Your Wialon account (token management) | `local.properties`, n8n `seed` |
| **Postgres database** (`PROV_DB_URL`) | Your own Neon project *or* a local `postgres:16` container | `psql`, n8n Postgres credential |
| **`HAMS_CLAIM_SECRET`** | You invent it (any strong string) — must be identical on app + n8n | `local.properties`, n8n IF node |
| **n8n owner login** | You create it on first run of n8n | n8n editor |
| **`MANUAL_CLAIM_URL` / `RELEASE_URL`** | Derived from *your* n8n host/tunnel | `local.properties` |

> 🔴 These are secrets or account-specific. Keep them in `local.properties` (gitignored) or inside
> n8n. **Never commit them and never put them in a zip you send to others** — share
> `local.properties.example` instead. If one leaks, rotate it.

### Preset for the shared platform — 🟢 info only
These already point at the shared Wialon gateway. Use them as-is; you only change them if **your**
Wialon account lives on a different Wialon server.
| What | Value | Note |
|---|---|---|
| IPS gateway host | `185.213.1.24` | shared Wialon Hosting gateway (`nl2.gpsgsm.org`) |
| IPS gateway port | `20332` | Wialon IPS v1.1 |
| IPS DNS | `nl2.gpsgsm.org` | same host by name |
| Protocol | Wialon IPS v1.1 (16-field `#D#`) | — |
| Hardware type | `Wialon IPS` (id `600002235`) | set on each Wialon unit |

> 🟢 Routing on this shared gateway is by the unit's **Unique ID** — your cuts land on *your*
> account's unit, not anyone else's, as long as your unit ids are your own. The IPS login carries
> **no device password** (`;NA`), so keep your unit ids private. See [CONTEXT.md](CONTEXT.md).

---

## 🛠️ Follow this path — build then test

Work through the phases **in order, opening the exact file/section listed**. Each phase names what
must already be done before you start it — **finish a phase fully before moving on.** Check the box
when a phase's own success check passes.

### Build

- [ ] **Phase 0 · Understand** — read the overview so the pieces make sense.
  - Prereq: none · Open: **[COMPANY_HANDOFF.md](COMPANY_HANDOFF.md)**
  - Done when: you understand app ↔ n8n ↔ Wialon and the pairing flow.
- [ ] **Phase 1 · Configure** — clone and fill `local.properties` (no source edits).
  - Prereq: JDK 17, Android SDK, your 🔴 `WIALON_TOKEN` · Open: **[SETUP.md §1](SETUP.md)**
  - Done when: `local.properties` exists with your values.
- [ ] **Phase 2 · Build & install the app**.
  - Prereq: Phase 1; a device/emulator on `adb devices` · Open: **[SETUP.md §2](SETUP.md)**
  - Done when: the app launches and shows the PairingScreen.
- [ ] **Phase 3 · Stand up the backend** (n8n + Postgres).
  - Prereq: Docker Desktop, a 🔴 Postgres DB, `psql` · Open: **[provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)**
  - Done when: its §9 `curl` happy-path claim returns `200`.
- [ ] **Phase 4 · Connect the app to n8n**.
  - Prereq: Phase 3 · Open: **[SETUP.md §4](SETUP.md)**
  - Done when: the phone can reach `…/webhook/manual-claim` (401 without a key).
- [ ] **Phase 5 · Prepare the Wialon unit**.
  - Prereq: a 🔴 Wialon unit you can edit · Open: **[SETUP.md §5](SETUP.md)** + **[unit checklist](docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md)**
  - Done when: the unit's Unique ID = your unit id, Advanced filters applied.
- [ ] **Phase 6 · Pair the phone**.
  - Prereq: Phases 2–5; an OTP minted · Open: **[SETUP.md §6](SETUP.md)**
  - Done when: `hams_prefs.xml` shows `device_unique_id=<unit>`.

### Test

- [ ] **Phase 7 · Automated tests**.
  - Prereq: Phase 2 · Open: **[SETUP.md §7](SETUP.md)** → run, then tick **[TEST_CASES.md](TEST_CASES.md) §A**.
  - Done when: unit + instrumented tests are `BUILD SUCCESSFUL`.
- [ ] **Phase 8 · Backend contract tests**.
  - Prereq: Phase 3 · Open: **[TEST_CASES.md §B](TEST_CASES.md)** (curl cases TC-BE-01…09).
  - Done when: every webhook case returns its expected status.
- [ ] **Phase 9 · App acceptance + real push**.
  - Prereq: Phase 6; outdoors for GPS lock · Open: **[SETUP.md §8](SETUP.md)** + **[TEST_CASES.md §C–E](TEST_CASES.md)**.
  - Done when: a real cut with satellites > 0 lands on the correct Wialon unit.

When all 10 boxes are ticked the system is fully built and verified. Need a value or contract mid-way?
→ **[CONFIG_REFERENCE.md](CONFIG_REFERENCE.md)**.
