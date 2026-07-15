# Security Incident & Rotation Record — 2026-07-15

**What:** The company-hosted backend server (`aihub.klkgroup.com.my`, running the **n8n + PostgreSQL**
provisioning stack) was reported **hacked**. This document records the exposure, the actions taken,
and the rotations still outstanding.

**No secret values appear in this document.** Everything is referenced by name. Real values live in
`local.properties`, a password manager, or the n8n credential store — never here, never in chat,
never in a commit.

**Rule going forward:** rotate anything that ever appears in a chat, screenshot, ticket, or commit.

---

## 1. What the server held (blast radius)

The compromised stack held **identity data only**. Harvest/cut data was never on it — the app pushes
cuts straight to Wialon (`185.213.1.24:20332`), which never traverses this server. But the server
did hold secrets, one of which reached the Wialon platform where all cut data lives.

| Secret on the server | What it grants | Severity |
|---|---|---|
| **Wialon token** (`HAMS_TEST`) | **FULL access, no expiry.** Read all cut data, modify/delete units, delete messages, run commands on the production Wialon account. | 🔴 critical |
| `HAMS_CLAIM_SECRET` | Impersonate a HAMS phone to the webhooks (pairing still also needs a unit id + OTP) | 🔴 high |
| PostgreSQL password | Read/write the `units` registry: device fingerprints (Android IDs), bindings, OTPs | 🟠 high |
| `KLK_hams_admin` (admin-release Basic Auth) | Free any unit without a phone or OTP | 🟠 medium |
| n8n owner login | Full control of all 7 workflows | 🟠 high |

**Assume the `units` registry was read** — every device fingerprint and binding on that server should
be treated as exposed.

---

## 2. The app is NOT compromised through the server

Verified in code (2026-07-15):

- **No hardcoded URLs.** The app only reaches the n8n URL and Wialon IP compiled at build time. A
  hacked server cannot redirect it.
- **No code execution from responses.** No `eval` / `WebView` / `loadUrl` / dynamic class loading.
  A malicious webhook reply cannot run code on a handset.
- **Responses are read only as a status string** (`bound` / `released` / `bound_other` / `not_found`).
  The worst a hijacked n8n could do is answer `released` to make phones flush + log out — a nuisance,
  not data theft or device takeover.
- **Exported components** are the launcher activity and a `BOOT_COMPLETED` receiver; neither takes
  network input.

---

## 3. Actions taken (local + Wialon) — DONE

| # | Action | Evidence |
|---|---|---|
| 1 | **Revoked the full-access Wialon token** (`HAMS_TEST`) at Wialon. | Re-tested 2026-07-15: login **rejected** (error 8) on both `.com` and `.eu`. |
| 2 | **Issued a new Wialon token** (`HAMS_SEED`) — **read-only**, 90-day expiry. | Write test denied (error 7); read lists 4 units. |
| 3 | Updated the token in the **local** n8n seed workflow + `local.properties`. | Local seed ran green, 4 units. |
| 4 | **Rotated `HAMS_CLAIM_SECRET`** on the **local** stack (app + local n8n 3 IF nodes). | All 3 webhooks: new secret → accepted, old → 401. |
| 5 | Rebuilt + reinstalled the **local** test APK with the new secret. | Installed on `ALI-NX1`; pairing/verify work locally. |
| 6 | Confirmed the **old** secret is dead on all 3 local webhooks. | manual-claim / release / verify → 401 with old key. |

> **Publish gotcha (important):** editing an n8n IF node and **saving** does NOT update the live
> webhook — the running endpoint serves the last **Published** version. During this rotation the
> local webhooks kept accepting the OLD secret until the 3 workflows were re-**Published**. On the
> deployed server, the same trap will lock out every field phone if the workflows aren't republished.

---

## 4. STILL OUTSTANDING — deployed / company side

**Do none of these until IT confirms the server is clean.** Loading a new secret onto a still-
compromised box re-leaks it.

| # | Action | Notes |
|---|---|---|
| A | **Confirm the server is clean** and the breach closed. | Prerequisite for everything below. |
| B | Change the **Wialon account password** (`it.intern4@klk.com.my`). | A full-access token was exposed; revoking the token does not re-secure the account. |
| C | In Wialon → Tokens, **delete any token you don't recognise**. | A full-access token can mint others (attacker persistence). |
| D | Rotate the **PostgreSQL / Neon password**. | The provisioning DB password was exposed. The local n8n also connects to Neon — update both. |
| E | Change **`KLK_hams_admin`** (admin-release Basic Auth) and the **n8n owner login**. | Both lived on the server. |
| F | Update the deployed n8n **seed** node to the new `HAMS_SEED` token, and the **3 IF nodes** to the new `HAMS_CLAIM_SECRET` — then **Publish** all changed workflows. | Republish or field phones lock out (see §3 gotcha). |
| G | **Reflash the fleet** pointed at the company URL with the new `HAMS_CLAIM_SECRET`. | The secret is compiled into the APK; there is no OTA update. |

**Sequencing for F+G:** update deployed n8n IF nodes → Publish → then reflash phones. The window
between the two locks out old phones, so keep it short and planned.

---

## 5. Pre-existing weaknesses this exposed (fix in the next phase)

Independent of the breach, worth hardening:

- **Wialon token was over-scoped** (full access, no expiry) for a job that only reads a unit list.
  New token is read-only + time-limited; keep it that way — no full-access tokens.
- **OTP codes are stored in plaintext** with no issuer attribution (`admin_otp`). See the next-phase
  spec (`docs/superpowers/specs/2026-07-10-data-integrity-and-audit-design.md`, items C1/C2).
- **A dev tunnel (`cloudflared`) is not a production URL.** It dies on sleep/restart and is unfit for
  field phones — a fixed domain is required (see `SETUP.md`).

---

## 6. What is confirmed clean

- **The GitHub repo** — re-scanned: 0 real secrets, 0 Supabase. Workflow JSONs carry placeholders
  (`<HAMS_CLAIM_SECRET>`, `<WIALON_TOKEN>`) only.
- **`local.properties`** — gitignored; holds the new values, never committed.
- **The local dev stack and test phone** — fully rotated and working.

---

*Recorded 2026-07-15 by WYH. Contains no secret values.*
