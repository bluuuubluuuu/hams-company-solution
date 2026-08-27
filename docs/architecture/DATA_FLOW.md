<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# Data Flow

**Purpose:** Show which system owns each production flow so investigations start in the correct place.

## Cut-data flow

```text
Worker action
  -> Android app captures GPS, battery, timestamp, and count state
  -> Room / SQLite on the phone
  -> Wialon IPS gateway
  -> Wialon reports and downstream company reporting
```

The cut path remains usable offline until a push opportunity. It does not require n8n or PostgreSQL to record a cut.

## Device-identity flow

```text
Office OTP + Wialon unit id
  -> Android app
  -> company n8n webhook
  -> company PostgreSQL binding registry
  -> result returned to the app
```

The same administrative path is used for binding verification and release. A device release attempts to deliver pending cuts under its current unit before it strands any rows that could otherwise upload under a later assignment.

## Investigation boundaries

| Symptom | Start with |
|---|---|
| A press cannot record | Device GPS/permissions and app logs |
| Cuts remain pending | App push state, network, then Wialon IPS/unit configuration |
| Pairing, release, or verification fails | n8n workflow execution and PostgreSQL binding registry |
| Wialon has a message but reporting is wrong | Wialon unit sensors, filters, report configuration, and event-code dictionary |

Use [Operations runbook](../operations/OPERATIONS_RUNBOOK.md) for safe checks and [Event-code dictionary](../reference/EVENT_CODE_DICTIONARY.md) for message meaning.
