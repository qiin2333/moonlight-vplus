# Same-Device Backup Schema Fixture

The same-device backup package is a versioned JSON format used by
`ConfigurationSyncManager`. It is intentionally narrower than multi-device sync:
hardware-specific settings are backed up only for the same Android device, and
automatic restore ignores packages whose `backupDeviceKey` does not match.

`schema-v1.example.json` is the compatibility fixture. `schema-v2.example.json`
adds update metadata, a same-device backup key, and pairing state. Keep both in
sync with the sections exported by `ConfigurationSyncManager`:

- `defaultPreferences`
- `appLastSettings`
- `customResolutions`
- `sceneConfigs`
- `appViewPreferences`
- `hiddenApps`
- `crownProfiles`
- `pairing`

Automatic local snapshots reuse the same schema and live in the app-private
`files/config-sync/latest.json` path at runtime. They can also be mirrored to a
user-selected Storage Access Framework folder. The external folder keeps a
convenience `moonlight-vplus-config-sync.json` file and a stable
`moonlight-vplus-device-backup.<backupDeviceKey>.json` same-device file.
Automatic restore reads only matching same-device packages before writing the
latest backup back. Legacy `moonlight-vplus-config-sync.<deviceId>.json` files
are recognized only when their metadata matches this device.
Snapshot metadata such as toggles, tree URIs, last-written timestamps, and
scheduler state is intentionally not portable.

Deleted preferences are exported as `type: "deleted"` tombstones with the same
`updatedAt`/`updatedBy` metadata as ordinary values, so removals are not
resurrected by older snapshots. Imported preference values and tombstones are
also recorded in the local tracked-key set so this device keeps re-exporting the
latest state.

Crown profiles carry stable `profileId` values and the same update metadata;
newer payloads replace older payloads for the same profile, and deleted profiles
are kept as tombstones until a newer local backup supersedes them.

The `pairing` section stores the client `uniqueid`, client certificate/private
key files, remembered host records, server certificates, and pair names. Pairing
state is treated as a whole-device snapshot using its own `updatedAt` value, so a
newer local deletion does not get merged with an older host list.

When background backup is enabled, portable SharedPreferences changes, Crown
profile database writes, and computer database writes request a debounced backup
immediately. The scheduler still keeps SAF folder observation, short in-process
polling, and an AlarmManager fallback so same-device restores and process
restarts converge automatically. SAF live
observation registers tree, document, and child-document URIs to handle providers
that notify at different levels.
If a local or remote trigger arrives while an in-process backup is already running,
the scheduler records a pending request and immediately runs one more pass after
the current pass finishes, instead of waiting for the next polling interval.
The application starts the scheduler during process startup before certificate
warmup, so a matching external backup can restore the old client identity before
a new certificate is generated.
Boot, package replacement, quick-boot, and user-unlocked broadcasts also reschedule
backup so persisted SAF folders recover after restart and credential unlock.
AlarmManager fallback is adaptive: initial passes, failures, local changes, and
write-back convergence use a short follow-up window, then settle back to a
lower-frequency idle cadence after a clean backup pass.
Configuration backup uses an app-private file lock inside `ConfigurationSyncManager`
so manual snapshots, background backup, and multi-process entry points cannot
restore and write the same external folder concurrently.
The most recent background backup result is stored as local metadata for the
settings status row; it is never exported into a portable package.

When adding a new portable section or changing typed values, update the fixture and
`ConfigurationSyncSchemaTest`.
