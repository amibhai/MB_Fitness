# Progress

Build order and "done when" for each phase: SPEC §17. Run `scripts/check.sh` before finishing a phase.

| Phase | Status | Notes |
|---|---|---|
| 0 Setup | Done, 5 Nov 2026 | Debug and release builds, lint clean. Manifest, dependency and source checks each confirmed to fail on a deliberate violation. |
| 1 Domain | Done, 5 Nov 2026 | All of §15's unit tests pass (183). `domain/` line coverage 98.9%, enforced at 90%. |
| 2 Data | Done, 5 Nov 2026 | Schema v1, transactional payments, undo, recycle bin, import, Aadhaar storage. 252 unit and integration tests. Keystore tests pass on an emulator (API 36): run them with `./gradlew connectedDebugAndroidTest`. |
| 3 Shell | Done, 5 Nov 2026 | Theme, fonts, icons, components, navigation, splash, lock screen, PIN setup. 284 tests. Checked on a Pixel 8 (Android 17) in light, dark and 130% text; cold start about 0.6-0.8 s on a debug build. |
| 4 Members and payments | Done, 6 Nov 2026 | Home, member detail, add and edit, record payment, UPI QR, payment edit and delete, Today's closing, plans, recycle bin, and the Aadhaar field. 310 tests. All flows checked on a Pixel 8. With 300 members on a debug build: 1.09% janky frames, 95% of frames within 15 ms. |
| 5 SMS and scheduling | Done, 6 Nov 2026 | Outbox, pacing, retries, daily run, alarms and watchdog, Messages, Broadcast, Templates, Quiet days, Send SMS. On the Pixel: 3 real test SMS to Swastik's number (approved), sent 3.7 s and 4.5 s apart, all delivered; running the day again sent nothing new. |
| 6 Onboarding and safety | Done, 6 Nov 2026 | Onboarding, Settings, Health check, backup, restore, CSV, backup password, PIN management, Help, About. Backup, wipe, restore gives a byte-identical file. On the Pixel, 35 checks: real backup files with and without a password, restores with the Keystore key gone, the Aadhaar number never in plain text or the system log, and Health check states as permissions are switched off and on. |
| 7 Reports and widget | Done, 6 Nov 2026 | Numbers match a hand-worked dataset. On the Pixel: Reports in light, dark and 130% text, and picking a month. The widget on the real home screen went from "1 overdue" to "0 overdue" straight after a payment. The first try showed a bug, now fixed. |
| 8 Hardening | Done apart from what needs Swastik | Storage-full guard, crash-proof file pickers, unused icons removed, release signing from `keystore.properties`, final permission and dependency audit, `docs/HANDOVER.md`. 612 tests. On the Pixel: the R8 release build (4.1 MB, signed with the local debug key for the check) installs over USB, cold-starts in 0.24 to 0.48 s, and every main screen works. A save failing on a full phone shows a message, and the app carries on. |
| Final hardening (1.0.1) | Done, 6 Nov 2026 | A full review for running without a developer around. It recovers by itself, or says what to do, after a screen failure, a crash, repeated crashes, a damaged database or settings file, a wrong clock, a full phone, a dual-SIM phone that asks each time, and a late-night catch-up run. Adds "Send a copy" and the app's own daily copies. 687 tests, lint clean, `domain/` coverage 99.3%. On the Pixel: 61 checks on a test build, including a garbage database, a database with wrecked pages, a garbage settings file, a real crash and three in a row. 46 more on an R8 build, including backup and restore, payment edits, and the outbox and daily-run workers starting after an update. The watchdog is confirmed scheduled, with its class and constructor intact in the release, but it wasn't seen running, because WorkManager won't run periodic work before its time. Not tried with real apps: sending a copy to Drive or WhatsApp, because this phone's share sheet lists real people. That's on the go-live checklist. See `docs/DECISIONS.md`. |

## Open items to close before release

Only Swastik can do these. `docs/HANDOVER.md` has the steps.

- Back up `mbfitness-release.jks` and `keystore.properties` (made 6 Nov 2026) in two places, and write the password from `keystore.properties` on paper. A lost key means the app can never be updated. Keep each release's `mapping.txt` too: crash details can't be read without it.
- Send bhaiya `dist/MB-Fitness-Club-1.0.1.apk` (no USB needed: `docs/HANDOVER.md` section 4) and run the go-live checklist, including a real **Send a copy** to Drive or WhatsApp.
- Register `com.swastik.mbfitness` for developer verification before 2027.
- Fill in `DEV_CONTACT` (About screen) and supply a real logo for the launcher icon, which is a placeholder "MB" mark.
- Try it on a Xiaomi or Redmi and a Samsung, which stop background apps the hardest.
- Have someone qualified confirm the Aadhaar design before go-live. It's a reading of the rules, not legal advice.
