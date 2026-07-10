# ⛔ Superseded — do not run these

These are the 7 original numbered migrations. They are kept for **provenance only**.

**Run [`../hams_setup.sql`](../hams_setup.sql) instead.** It is these files concatenated in their
true apply order, with the SOP naming applied, and it is idempotent.

## Why they are still here

Each file carries a header comment explaining *why* it exists. That is the design rationale, and it
is worth reading before the SQL itself — particularly `007_drain_lease.sql`, which explains the
flush window that protects a departing phone's unsent harvest cuts.

## Two quirks they document

| Quirk | Reason |
|---|---|
| There is no `002` | Withdrawn during development, never applied. The gap is deliberate. |
| `007` runs before `006` | `006_check_binding.sql` reads columns that `007_drain_lease.sql` adds. |

## Apply order (already baked into `hams_setup.sql`)

`001` → `003` → `004` → `005` → `007` → `006` → `008`
