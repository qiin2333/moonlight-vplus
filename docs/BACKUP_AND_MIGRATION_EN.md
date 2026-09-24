# Backups, restore, and profile sharing

Moonlight V+'s Backup and Sync saves settings and pairing state for the current device. Sharing one Crown profile is a separate workflow. **A full backup contains sensitive pairing identity; do not publish it as a profile package.**

| Your goal | What to do |
| --- | --- |
| Restore settings on this device | Save a full backup and keep its password |
| Move to a new device | Pair the computers again and export any Crown profiles you need |
| Share a control layout | Use only the Crown profile export or share action |

## What the backup covers

A full backup may contain settings, game presets, Crown profiles, paired computers, and device identity. Before restoring, the app lists what can be recovered; use that preview as the guide. Full backups are mainly for restoring the original device. Do not send one to another person or expect it to restore pairing on a new device.

## Save a restorable copy

1. Open Backup and Sync in settings, use Save Backup File, and note where the file is stored.
2. To keep copies in a selected folder, choose Backup Folder, set a backup password, and save a backup there. You can choose a folder provided by a cloud-drive app; that app handles uploading the file.
3. Keep the password separately. A password remembered on this device may be unavailable after reinstalling or clearing app data; a protected backup cannot be previewed or restored without it.
4. Check the automatic-sync status or manual-save result periodically so a failed write is not mistaken for a backup.

The local fallback copy helps with recovery on this device; it is not a substitute for an external copy when the device is lost or damaged.

## Restore on the original device

In Backup and Sync, choose Restore from Backup File or Restore Latest Backup from Folder. Enter the password and review the preview before applying it. After pairing data is restored, the app may ask for a restart; hosts that fail to restore may need pairing again. Keep a copy of your current state before restoring because existing settings can change.

## New device and Crown sharing

A full backup is tied to the device that created it. On a replacement device, install the app and pair hosts again, then migrate settings individually or export the needed Crown profiles through their dedicated flow. For public sharing, review the profile package and never send a full backup, backup password, pairing certificate, or host address.

If backup or restore fails, report client and Android versions, storage location, error text, and preview result in [Issues](https://github.com/qiin2333/moonlight-vplus/issues). Do not upload the raw backup file.
