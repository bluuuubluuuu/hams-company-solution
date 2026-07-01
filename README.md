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

---

## 📖 Documentation menu

Read top-to-bottom the first time; after that, jump to what you need.

### Setup & operation
| Document | Purpose | Read when |
|---|---|---|
| **[SETUP.md](SETUP.md)** | End-to-end build walkthrough: from a fresh clone to a paired phone pushing real cuts. Prerequisite-gated, step by step. | You are setting the whole thing up. **Main guide.** |
| **[provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)** | How to build the n8n + Postgres admin backend — fast (import ready workflows) or from scratch (node-by-node), plus a gotchas list and curl test matrix. | You are standing up the backend (called from SETUP §5). |
| **[provisioning/README.md](provisioning/README.md)** | Index of the `provisioning/` folder (SQL, workflow JSONs) and what each file is. | You want to know what's in the backend folder. |
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

## 🛠️ Build order (with prerequisites)

Do these in order. Each step names its prerequisites — **finish a step fully before the next.**

| # | Step | Prerequisite before you start | Guide |
|---|---|---|---|
| 1 | Get the code & fill `local.properties` | JDK 17, Android SDK, your Wialon token | [SETUP §1–2](SETUP.md) |
| 2 | Build & install the app | Step 1 done; a device/emulator on `adb` | [SETUP §3](SETUP.md) |
| 3 | Stand up the backend (n8n + Postgres) | Docker Desktop, a Postgres DB, `psql` | [BUILD_ADMIN_BACKEND](provisioning/BUILD_ADMIN_BACKEND.md) |
| 4 | Prepare the Wialon unit | A Wialon unit exists; you can edit it | [SETUP §5](SETUP.md) + [unit checklist](docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md) |
| 5 | Pair the phone | Steps 2–4 done; an OTP minted | [SETUP §6](SETUP.md) |
| 6 | Run tests & verify a real push | Step 5 done; outdoors for GPS lock | [SETUP §7–8](SETUP.md) |

New here? Read [COMPANY_HANDOFF.md](COMPANY_HANDOFF.md) for the overview, then start at
[SETUP.md](SETUP.md).
