# Working rules — MB Fitness Club (Android)

Offline fee-management + automatic SMS reminder app for **MB Fitness Club** (owner: Jeetu Bhaiya). App by Swastik.
**The full spec is `docs/SPEC.md`. Read it before any work. It wins over anything else except this file's hard rules.** Deliberate deviations are logged in `docs/DECISIONS.md` and override the spec text they name.

## Hard rules (never break)
1. **No network.** The `INTERNET` permission must never appear in the merged manifest (library-merged normal permissions like WorkManager's `ACCESS_NETWORK_STATE`/`WAKE_LOCK` are fine). No Firebase, Play Services, analytics, crash reporting, ads, or any SDK needing an API key/account.
2. **Permissions are exactly SPEC §11.2.** Never add RECEIVE_SMS, READ_SMS, accessibility, notification listener or USE_EXACT_ALARM (they trigger Play Protect / restricted-settings blocks on sideloaded apps).
3. **Only `OutboxWorker` sends SMS**, always through `Pacing` (≤ 25 parts / 30 min, ≤ daily cap). Never call `SmsManager` anywhere else.
4. **All business rules live in `domain/`** (pure Kotlin, no Android imports) and are unit-tested. UI and workers only call into them.
5. **Dates are epoch days in Asia/Kolkata; money is whole rupees as `Long`.** No floats for money, no timestamps for business dates.
6. **Every money change is one Room transaction** and ends with `recalculateMember`. Never patch expiry incrementally.
7. **Room:** `exportSchema = true`; every schema change ships a `Migration` + test. Never `fallbackToDestructiveMigration`.
8. **Free, pinned dependencies only** (versions in `gradle/libs.versions.toml`, no `+`). Licences: Apache-2.0 / MIT / BSD / OFL.
9. **Debug builds force Test mode** (SMS only to the trainer number). Never send real SMS from tests.
10. Never commit `mbfitness-release.jks` or `keystore.properties`.
11. **The full Aadhaar number is never stored unencrypted, logged, sent by SMS or exported in plain form.** It exists in plaintext only in memory, briefly, on entry and reveal, and inside password-encrypted backups (SPEC §7.10, §12).

## Conventions
- Package `com.swastik.mbfitness`; layout per SPEC §5.3. Manual DI in `App.kt`.
- ViewModels expose `StateFlow<UiState>`; IO on `Dispatchers.IO`.
- Clock injected via `TodayProvider`; never call `LocalDate.now()` directly outside it.
- Colours from `BrandColors`/`StatusColors`; font Plus Jakarta Sans (bundled); icons = bundled Material Symbols Outlined vectors; no emoji in UI.
- User-facing text: short, plain English (templates may be Hinglish). Money shown as "₹1,200" in the UI, "Rs 1200" in SMS.

## Workflow
- Work one phase at a time (SPEC §17). Before finishing a phase run `scripts/check.sh` (= `./gradlew testDebugUnitTest lintDebug assembleDebug`) and fix everything.
- Log decisions/deviations in `docs/DECISIONS.md` (date · what · why). Update `docs/PROGRESS.md` after each phase.
- If the spec is ambiguous, pick the safer behaviour (never lose data, never send extra SMS), note it in DECISIONS.md, continue.
- Commit after each green phase with a clear message.
