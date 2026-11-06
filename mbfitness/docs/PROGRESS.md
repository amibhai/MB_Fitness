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
| 7 Reports and widget | Done, 6 Nov 2026 | Numbers match a hand-worked dataset. On the Pixel: Reports in light, dark and 130% text, picking a month, the widget registered with Android. The widget hasn't been seen on a home screen yet. |
| 8 Hardening | Done apart from what needs Swastik | Storage-full guard, crash-proof file pickers, unused icons removed, release signing from `keystore.properties`, R8 release build (4.0 MB), final permission and dependency audit, `docs/HANDOVER.md`. 610 tests. **Still to do on the Pixel: the release build itself, and the storage-full check again.** |

## Open items to close before release

Only Swastik can do these. `docs/HANDOVER.md` has the steps.

- Create `mbfitness-release.jks` and `keystore.properties`, then back the key up in two places. A lost key means the app can never be updated.
- Build the signed release, install it on bhaiya's phone over USB, and run the go-live checklist.
- Register `com.swastik.mbfitness` for developer verification before 2027.
- Fill in `DEV_CONTACT` (About screen) and supply a real logo for the launcher icon, which is a placeholder "MB" mark.
- Try it on a Xiaomi or Redmi and a Samsung, which stop background apps the hardest.
- Have someone qualified confirm the Aadhaar design before go-live. It's a reading of the rules, not legal advice.
