<!-- DRAFT — production-content review required. -->
# Operations Runbook

**Purpose:** Triage production problems without exposing credentials or changing data ownership accidentally.

## First-response rules

1. Record the unit id, device identifier, time window, app version, and observed symptom.
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

## Android evidence

Use a company-authorised workstation and connected device. Useful commands are:

```powershell
adb logcat -s HAMS_UI
adb logcat | Select-String 'HAMS|Push|Provision'
adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "SELECT * FROM tasks ORDER BY updated_at DESC;"
```

The device database is evidence. Copy it before any destructive repair, and do not modify production rows casually.

## Reference points

- [System overview](../architecture/SYSTEM_OVERVIEW.md)
- [Data flow](../architecture/DATA_FLOW.md)
- [Device provisioning](DEVICE_PROVISIONING.md)
- [Configuration reference](../reference/CONFIGURATION_REFERENCE.md)
- [Event-code dictionary](../reference/EVENT_CODE_DICTIONARY.md)
