# config/

Local-only n8n workflow exports. `config/*.json` is gitignored: these files carry
the live webhook path, credential ids and recipient address copied straight from
the server, and must not be committed.

## `G_PM_IT_IOT_HAMS_DEVICEOTP.json`

Replacement for the live OTP-request workflow on aihub. Built from that
workflow's own export, so the webhook path, Postgres credential, Outlook node and
workflow id are the real ones — importing it **updates the live workflow in
place** rather than creating a second one.

Adds nine nodes that seed the unit being paired before the OTP is issued, so a
newly created Wialon unit no longer needs a human to run the seeding flow first.

### Before importing

1. Fill in `<WIALON_TOKEN>` in the `Wialon login` node. Copy it from
   `G_PM_IT_IOT_HAMS_SEED`; it is not in this file.
2. Keep the original export as the rollback. Re-importing it restores the
   previous three-node workflow exactly.
3. Test with an already-seeded unit first — that path skips Wialon entirely and
   must behave exactly as today.

### Not included, deliberately

- **No `x-hams-key` check.** The live webhook has none. Adding one is worth
  doing, but if the server-side secret does not match what is compiled into the
  handsets, pairing breaks fleet-wide on import. Separate change, tested
  separately.
- **No scheduled seeding.** A daily run would catch units created but never
  paired. It was dropped here because the repo's snapshot of
  `G_PM_IT_IOT_HAMS_SEED` references Postgres credential `Cas1s6GCZh1blzuK`
  while the live OTP workflow uses `hmYKfQtR3TiMY2nG` — same display name,
  different credential. Resolve which is correct before rebuilding it.
