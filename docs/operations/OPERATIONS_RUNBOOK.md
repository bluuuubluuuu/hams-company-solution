<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# Operations Runbook

**Purpose:** Triage production problems without exposing credentials or changing data ownership accidentally.

## First-response rules

1. Record the unit id, device identifier, time window, app version, and observed symptom. The app version is the bottom line of the count screen — ask the worker to read it out; no cable or admin access is needed.
2. Do not release or re-pair a device merely to troubleshoot a pending push.
3. Do not edit app source or commit real credentials to diagnose production.
4. Confirm the device still owns the intended Wialon unit before any release/replacement action.

## Common symptoms

| Symptom | Safe first checks | Escalate when |
|---|---|---|
| Count buttons unavailable | GPS permission, device location setting, fresh GPS indicator | GPS remains unavailable after device/location checks |
| Count recorded but not uploaded | Pending-task count, network availability, app push outcome | Wialon rejects or never stores a confirmed send |
| Pairing or release fails | OTP freshness, selected unit id, n8n workflow execution, binding result | Registry data conflicts or an office override is needed |
| Unit receives data but report is wrong | Wialon unit filters, sensors, report configuration | A production report/sensor change is required |
| Report count lower than the presses made | Compare the report against `work_count` in the raw unit messages. Wialon retains same-timestamp messages, so this is a notification/report question, not lost data | The report must be rebuilt on `work_count` deltas |
| New unit rejected at pairing | Registry row for that unit id; the id must match `^OC\d{3}_H_[A-Za-z0-9]+$`; the `G_PM_IT_IOT_HAMS_DEVICEOTP` execution log for the code request | Seeding ran but wrote no row, or the unit is absent from Wialon |
| Seeding silently not happening | The `Wialon login` and `Wialon find this unit` nodes in the `G_PM_IT_IOT_HAMS_DEVICEOTP` execution. Both continue on error, so OTPs keep arriving while nothing is seeded | The Wialon token is rejected or the unit search returns nothing for a unit known to exist |
| Presses refused during normal counting (1.3) | The 1.5 s press rate limit is doing this by design; the refusal cue confirms it | Field pace genuinely exceeds one press per 1.5 s — reconsider the limit or move reporting to `work_count` |

## Android evidence

Use a company-authorised workstation and connected device. Useful commands are:

```powershell
adb logcat -s HAMS_UI
adb logcat | Select-String 'HAMS|Push|Provision'
adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "SELECT * FROM tasks ORDER BY updated_at DESC;"
```

To identify a build from an APK rather than a running handset:

```powershell
aapt dump xmltree hams-1.2.apk --file AndroidManifest.xml   # versionCode, versionName, com.klk.hams.author
apksigner verify --print-certs hams-1.2.apk                 # signer fingerprint
unzip -p hams-1.2.apk assets/NOTICE.txt                     # authorship record
```

A release build not signed with `98fb0136385382720339d88aec7db90df8e769101f78a9e22097f28617d44f73` did not come from this project.

The device database is evidence. Copy it before any destructive repair, and do not modify production rows casually.

## Reference points

- [System overview](../architecture/SYSTEM_OVERVIEW.md)
- [Data flow](../architecture/DATA_FLOW.md)
- [Device provisioning](DEVICE_PROVISIONING.md)
- [Configuration reference](../reference/CONFIGURATION_REFERENCE.md)
- [Event-code dictionary](../reference/EVENT_CODE_DICTIONARY.md)
