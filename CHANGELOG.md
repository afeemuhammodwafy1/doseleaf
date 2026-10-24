# Changelog

## 1.0.0 (versionCode 1)

Development-source update; version name/code remain unchanged.

- Native Kotlin/Compose and Room local-data foundation.
- Medicine add/edit with multiple 12-hour picker-based reminder times, optional food timing and custom interval.
- Optional prescription photo URI attachment.
- Family profile editing/deletion and safe medicine recycle-bin handling.
- Dose history correction UI and reminder alarm Taken/Skip controls.
- Home screen focused on medicine list and daily summary.
- Alarm notification icon, sound/vibration preference and full-screen alarm activity.
- Exact-alarm denied fallback to Android's less precise allow-while-idle alarm scheduling.
- Settings subpages, manual Google Drive file-save flow, JSON backup/restore, CSV/PDF exports.
- MIT licence and public GitHub documentation.

This is not a verified release build. See `BUILD_STATUS.md`.


### Follow-up completion work (still version 1.0.0 / code 1)

- Added one-time medicine expiry notifications 7 days before and on the entered expiry date.
- Reschedule expiry reminders after reboot/time changes and when medicines are added, edited, restored or deleted.
- JSON backup schema v6 can include readable prescription image bytes and restore them to app-private storage through FileProvider.
- Added FileProvider paths for safely opening restored prescription images.

These changes require Android Studio compilation and device QA; no build success is claimed here.

- Added expiry reminders for 7 days before and on the entered expiry date.
- Updated JSON backup to schema v6 to embed readable prescription photos and restore them to app-private storage using FileProvider.
- Expanded Bengali translations across common UI labels, Settings, onboarding guide and food timing.
- Added FileProvider configuration and updated repository QA notes.

## Unreleased — UX and behavior fixes

- Moved medicine forms to full-screen navigation and anchored Save/Cancel actions at the bottom.
- Added prominent contextual validation for medicine name, reminder times, and profile selection.
- Filtered Home reminders to the upcoming hour and made dose logging idempotent per scheduled occurrence.
- Added a top-level Settings entry and confirmed bulk deletion for Recycle Bin items.
- Added a Google Drive-targeted backup share flow with a Recommended label.
- Protected the default profile from deletion and added profile-specific details pages.
- Integrated first-run profile name entry into the first step of a five-step onboarding guide.
