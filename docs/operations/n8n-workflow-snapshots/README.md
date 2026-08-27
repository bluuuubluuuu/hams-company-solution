<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# n8n Workflow Snapshots

These seven JSON files are sanitized recovery/reference exports for the company-managed n8n workflows. They are not a local deployment guide and must not be imported over a live workflow without an approved change, backup, and rollback plan.

Real credentials are intentionally absent. The company n8n server remains the operational source of truth; export an updated sanitized snapshot after an approved production workflow change.

## `G_PM_IT_IOT_HAMS_DEVICEOTP` — seeding on code request (20 August 2026)

`G_PM_IT_IOT_HAMS_DEVICEOTP.json` is a sanitized export of the workflow **as
deployed**, replacing the earlier browser-form version that only issued a code.

It now seeds the unit being paired before issuing the OTP. Nine nodes sit between
the webhook and the existing `issue_otp` query:

```
Webhook -> Parse unit -> If unit id well-formed --false------------------+
                            +- Already seeded? -> If not yet seeded --false--+
                                 +- Wialon login -> find -> pick -> If matched --false--+
                                      +- Seed unit ----------------------------+
                                                                               v
                                                        Execute a SQL query (issue_otp)
                                                                 +- Send a message
```

Four branches converge on the OTP node, so a code is never conditional on
seeding succeeding. Both Wialon nodes continue on error: a Wialon outage costs
the seeding, never the code — which also means such a failure is **silent** and
visible only in the execution log.

The registry is checked before Wialon, so a unit already present skips the
external call entirely.

### Placeholders to fill before import

| Placeholder | Source |
|---|---|
| `<OTP_REQUEST_WEBHOOK_PATH>` | The live path. It must not change — `OTP_REQUEST_URL` is compiled into every deployed APK. |
| `<WIALON_TOKEN>` | The same token used by `G_PM_IT_IOT_HAMS_SEED`. |
| `<OTP_RECIPIENT_EMAIL>` | The office administrator’s mailbox. |
| `<CREDENTIAL_ID>` | Re-select the Postgres and Microsoft credentials in n8n after import. |

### Not included

No `x-hams-key` check. The live webhook has none, so the endpoint is protected
only by the obscurity of its path — and it can now trigger Wialon calls. Adding
the check is worthwhile but must be tested separately: if the server-side secret
does not match what is compiled into the handsets, pairing breaks fleet-wide.

