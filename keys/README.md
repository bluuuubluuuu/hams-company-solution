# Release Signing Key

Expected local filename: `keys/hams-release.jks`.

The real keystore is ignored by Git and must never be committed, copied into documentation, or shared through chat. Obtain it only from the approved company release-key owner or company secret vault.

Configure the keystore path and passwords in untracked `local.properties` or approved CI secrets. Do not regenerate or replace the signing key without a controlled migration: changing the signing identity can require affected devices to be released and paired again.
