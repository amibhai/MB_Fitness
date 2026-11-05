# Decisions and deviations

Date · what · why. Where an entry names a deliberate deviation, it overrides the SPEC text it names.

## 5 Nov 2026: scope and setup

- **Aadhaar number added to every member.** Requested by Swastik; not in the original spec. Optional with its own consent tick, encrypted at rest, masked on screen. Written into SPEC §7.10 and §12 and docs/RULES.md hard rule 11. Why optional: regulation 5 of the Aadhaar (Sharing of Information) Regulations 2016 requires a legal basis to make it mandatory, and no law requires it for a gym. This is a reading of the rules, not legal advice; have someone qualified confirm it before go-live.
- **Full Aadhaar in backups only when a backup password is set.** Chosen by Swastik. Without a password, the file holds the last 4 digits plus ciphertext that only this phone's Keystore key can open. Why: backups are readable files that may sit in a Google Drive folder, and regulation 6 requires the numbers to be kept secure.
- **No prototype exists.** SPEC §10.1 and Phase 3 mention one, but none was supplied. Swastik chose to build from the SPEC §10 tokens and screen descriptions.
- **Project root stays `mbfitness/`,** not the repo root as SPEC §0 says. Why: that is where the documents were placed, and nothing requires a move.
- **Work happens on branch `build/release-1.0`,** one commit per green phase, no push unless asked.
- **Toolchain:** AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21, KSP 2.3.13, compile and target SDK 37. Built from the command line. Android Studio 2025.1.3 on this machine can't open AGP 9.5; opening the project in Studio needs Rabbit 2 (2026.2.2).
- **Kotlin warnings are errors across the whole `app` module,** not only `domain/` (SPEC §14). Why: the compiler can only do this per module, and SPEC §5.3 requires a single module. A stricter rule still satisfies the spec.
- **Hard rules are enforced by the build.** `verifySourceRules`, `verify<Variant>MergedManifest` and `verify<Variant>Dependencies` run with `assemble`, `lint` and unit tests. The manifest check uses an allow-list, so a permission added by a library fails the build until someone reviews it.
- **`READ_CONTACTS` is not declared.** SPEC §11.2 allows it only if multi-pick import needs it. The system contact picker needs no permission, so it stays off the allow-list.
- **`fullBackupContent` added alongside `dataExtractionRules`** (SPEC §12). Why: `dataExtractionRules` only applies on Android 12 and up, and min SDK is 26.

## 5 Nov 2026: spec gaps found while planning

Following these parts of the spec literally would cause bugs. Each fix lands in the phase named.

| Spec | Problem | Fix | Phase |
|---|---|---|---|
| §7.3–7.4 replay uses "today", the current renewal rule and current plan months | Editing a plan or the rule would silently change every member's expiry, and the result would shift with the calendar | Replay from `paidOn`, using `planMonths`, rule and restart days saved on each payment | 1, 2 |
| §6 `nextReceiptNo` in DataStore | It can't share a Room transaction, so a crash could reuse or skip a number (hard rule 6) | Keep the counter in a Room table | 2 |
| §7.9 OPENING payments use receipt numbers | Importing 50 members burns MBF-0001 to 0050 with no money received | OPENING has no receipt number; expiry comes from the CSV date | 1, 2 |
| §8.2 dedupe keys need `n`, payment ID and year; §6 only has `forExpiry` | Repeat reminders can't be deduped | `Message.dedupeKey` with a UNIQUE index, so the database itself blocks duplicates | 1, 2 |
| Nothing cancels queued reminders | A member who just paid can still get "fee overdue", and "Stop SMS" misses queued messages | Payment, freeze, Left and Stop SMS cancel that member's queued reminders in the same transaction; next-day leftovers are re-worded so `{days_overdue}` stays true | 2, 5 |
| §7.3 Undo lasts 5 s, sending takes 3–5 s | A thank-you SMS can go out for a payment that was undone | Thank-you waits 10 s; Undo cancels it | 2, 5 |
| §8.5 stuck SENDING rows are re-queued | Double send if the radio had already taken the message | Record `submittedAt`. If set with no result: FAILED with manual Retry. If not set: re-queue | 5 |
| §8.2 catch-up has no age limit | The first run after import could text long-gone members | Skip schedule points more than 7 days old | 1 |
| §8.11 `→` in the test prefix | It forces Unicode, so test SMS use more parts and don't reflect real pacing | Use `[TEST > Rahul]` | 5 |
| §12 password + automatic weekly backup | The background job has no one to type the password | Store the derived key wrapped by the Keystore | 6 |
| §9 `LOCKED_BOOT_COMPLETED` | Database and settings are unreadable until first unlock, so touching them crashes | No-op while locked; `BOOT_COMPLETED` re-arms; `App.kt` DI is lazy | 5 |
