# LOCAL-RUN — quick localhost bring-up

Everything runs on one PC: local **n8n** (Docker) + **Postgres** + the Android app. The app talks
to n8n only for pairing; cut data goes phone → Wialon directly.

This is just the running order — each step is documented in full elsewhere:

1. **Backend** — apply SQL, start n8n, import/publish the workflows:
   → [provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)
2. **Expose n8n to the phone** (`adb reverse` or `cloudflared`) + point `local.properties`:
   → [SETUP.md §4](SETUP.md)
3. **Build & install the app**, then **pair** a device:
   → [SETUP.md §2 and §6](SETUP.md)

Values, ports, and the webhook contract: [CONFIG_REFERENCE.md](CONFIG_REFERENCE.md).
New here? Start at the hub: [README.md](README.md).

**Rig note:** Docker containers stop when the PC sleeps → `docker start hams-pg hams-n8n`, then
confirm `curl http://localhost:5678/healthz` returns `200`.

---
**Nav:** [🏠 Hub](README.md) · [Setup](SETUP.md) · [Backend](provisioning/BUILD_ADMIN_BACKEND.md) · [Config](CONFIG_REFERENCE.md) · [Tests](TEST_CASES.md)
