# Progress

Build order and "done when" for each phase: SPEC §17. Run `scripts/check.sh` before finishing a phase.

| Phase | Status | Notes |
|---|---|---|
| 0 Setup | Done, 5 Nov 2026 | Debug and release builds, lint clean. Manifest, dependency and source checks each confirmed to fail on a deliberate violation. |
| 1 Domain | Done, 5 Nov 2026 | All of §15's unit tests pass (183). `domain/` line coverage 98.9%, enforced at 90%. |
| 2 Data | Done, 5 Nov 2026 | Schema v1, transactional payments, undo, recycle bin, import, Aadhaar storage. 252 unit and integration tests. Keystore tests pass on an emulator (API 36): run them with `./gradlew connectedDebugAndroidTest`. |
| 3 Shell | Done, 5 Nov 2026 | Theme, fonts, icons, components, navigation, splash, lock screen, PIN setup. 284 tests. Checked on a Pixel 8 (Android 17) in light, dark and 130% text; cold start about 0.6-0.8 s on a debug build. |
| 4 Members and payments | Done, 6 Nov 2026 | Home, member detail, add and edit, record payment, UPI QR, payment edit and delete, Today's closing, plans, recycle bin, and the Aadhaar field. 310 tests. All flows checked on a Pixel 8. With 300 members on a debug build: 1.09% janky frames, 95% of frames within 15 ms. |
| 5 SMS and scheduling | Not started | |
| 6 Onboarding and safety | Not started | |
| 7 Reports and widget | Not started | |
| 8 Hardening | Not started | |

## Open items to close before release

- Phase 6: changing or removing the app PIN must ask for the current PIN first. Settings currently goes straight to "Choose a PIN".
- Phase 8: remove the `UnusedResources` icon exemption in `app/lint.xml`, then delete any icon still unused.
- Phase 8: the launcher icon is a placeholder "MB" mark until a real logo is supplied.
