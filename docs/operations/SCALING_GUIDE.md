<!-- DRAFT — production-content review required. -->
# Scaling Guide

**Purpose:** Add devices or operational coverage without changing the protocol, leaking secrets, or disrupting existing units.

## Adding a device

1. Prepare the signed app build and the company-approved app configuration.
2. Configure the Wialon unit using [Device provisioning](DEVICE_PROVISIONING.md).
3. Pair the device through the office OTP flow; do not use a compile-time unit id as the production identity.
4. Verify binding, a GPS-gated cut, and a safe Wialon receipt before field deployment.
5. Record the unit, assigned device, app version, rollout date, and responsible support owner in company operations records.

## Capacity and change rules

- Device identity is one unit to one Android device fingerprint at a time.
- Scale by provisioning additional Wialon units and following the same verification procedure, not by reusing an existing unit id.
- Keep Wialon filters and sensors consistent across every production unit.
- Treat changes to event codes, Wialon reporting, n8n workflows, or binding rules as controlled changes with an explicit test and rollback path.
- Keep secrets in company-approved secret storage; examples in this repository are placeholders only.

## Before a broad rollout

- Confirm Wialon capacity, report ownership, n8n workflow health, PostgreSQL backup/recovery ownership, and a support escalation contact.
- Trial the release with a small group of devices first.
- Review failures from the trial before adding further estates or sites.

See [Operations runbook](OPERATIONS_RUNBOOK.md) for incident handling and [System overview](../architecture/SYSTEM_OVERVIEW.md) for ownership boundaries.
