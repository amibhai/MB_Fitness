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

## 5 Nov 2026: choices made while writing the rules (Phase 1)

The spec was silent or ambiguous on these. Each picks the safer behaviour.

- **Which freezes count (§7.4).** A completed freeze adds its whole length if it began on or before the expiry day, including when it runs past expiry. Why: a member with 4 days left when frozen should have 4 days left when unfrozen. A freeze starting after expiry adds nothing.
- **A period starting inside a freeze.** Under rule B or a restart, a payment made during a freeze starts its period when the freeze ended. Why: otherwise those frozen days are lost.
- **A freeze can give an earlier date past the restart limit.** It can keep a member inside `restartAfterDays`, so they continue from the old expiry instead of restarting from the payment date. This follows from the rules as written, so it stays.
- **Planned reminders only go between 8 AM and 9 PM.** A late catch-up run holds them until morning. Why: the watchdog can run whenever the phone wakes up, and an SMS at midnight is how complaints start. Bhaiya's own broadcasts and manual SMS are not held.
- **A balance reminder stays 3 days from any other reminder to that member.** Why: otherwise a member could get a reminder and a balance reminder on consecutive days, and expiry reminders already include the balance.
- **A reminder needing a blank setting is held, not sent.** For example `{trainer_phone}` with no number set. It isn't marked as sent, so it goes once the setting is filled in. Why: never send a broken SMS and never lose a reminder.
- **A blank UPI ID removes the `UPI: {upi_id}` clause** and the reminder still goes. Why: the reminder is useful without it, and "UPI: ." is not.
- **Catch-up never substitutes a disabled template.** If the truthful wording's template is switched off, nothing goes.
- **`ON_DUE` always falls on the expiry day,** whatever its stored offset. Why: its wording says "today".
- **A missed birthday isn't sent late.** Why: "Happy Birthday" a day late reads as a mistake.
- **CSV dates also accept `1-2-2026` and `01/02/2026`.** Why: Excel often rewrites them. Two-digit years are refused because they are ambiguous.
- **The same Aadhaar twice in one import file is an error on the later row.**
- **`domain/` coverage is enforced by `verifyDomainCoverage`** (90% of lines), which `scripts/check.sh` runs.

## 5 Nov 2026: data layer (Phase 2)

Additions to the SPEC 6 data model, beyond the Aadhaar fields in SPEC 7.10 and the fixes in the table above:

- **`Member.activePhone`:** equal to `phone` until the member is deleted, then null, with a UNIQUE index. Why: SPEC 6 wants phones unique among non-deleted members. SQLite allows many NULLs in a UNIQUE column, so the database itself enforces this, and Room can declare it, unlike a partial index.
- **`Payment.planMonths`, `ruleApplied`, `restartAfterDays`:** saved when a payment is recorded. **`receiptNo` is nullable,** for OPENING rows.
- **`Message.dedupeKey`** (UNIQUE) **and `submittedAt`.** Cancelling a message clears its key, so a cancelled reminder can be planned again.
- **`counters` table:** holds the next receipt number, replacing `nextReceiptNo` in DataStore.
- **`MemberEventType.IMPORTED`.** Why: an imported member is active but isn't a new join, so Reports must tell them apart.
- **Settings added:** `packLanguage`, `backupPasswordHint`, `backupKeyWrapped`, `backupSalt`. `reminderTime` is stored as `reminderHour` and `reminderMinute`.

Behaviour the spec didn't cover:

- **Undo of a payment that brought back a member who had left also reverses the rejoin.** Why: otherwise the leftover REJOINED event would change how their next payment is calculated.
- **Renaming a member cancels their queued planned reminders.** Why: the text quotes the old name. The planner writes them again on its next run.
- **An import row needing a plan length with no active plan is refused:** "No 6-month plan. Add it in Plans first." Why: inventing a price would be worse than asking.
- **Imported members get today as their join date.** The CSV format has no join date, and their expiry comes from the file anyway.
- **Keystore work happens before a transaction opens.** It is slow, and a failure there must not stop the member being saved.

Testing notes:

- **`MigrationTest` uses `AndroidSQLiteDriver`.** Why: Room's default driver compares file names in a way that breaks on Windows paths under Robolectric. It doesn't affect the app on a phone.
- **Schemas are added to debug assets only.** Why: `MigrationTestHelper` reads them from assets, and under Robolectric only the variant's own assets are visible. They never ship in a release.

## 5 Nov 2026: app shell (Phase 3)

- **Testing happens on Swastik's Pixel 8 (Android 17, API 37), not emulators.** Swastik asked for this. It replaces the emulator checks in the plan, and it is the app's exact target version. Any setting changed for a check is restored afterwards. Ask before anything could send a real SMS.
- **Bottom navigation: Home, Today, SMS, Reports, Settings.** The spec doesn't define top-level navigation, and §3 wants Today's closing and Reports one tap away. Broadcast will be reached from SMS and from multi-select. "Members" and "Messages" were clipped at 130% text on the phone, so they became "Home" and "SMS", and labels now shrink to fit at larger sizes.
- **Every screen has the dark header bar,** so status bar icons are always light. The UPI QR screen will handle its own white background.
- **App lock.** Asks on a cold start and after more than 60 seconds away, so a trip to the contact or file picker doesn't re-ask. Five free wrong guesses, then a wait of 30 seconds that doubles up to 15 minutes. The count is stored, so closing the app can't skip it. The PIN length is stored so the pad can check without an OK key.
- **PIN and recovery code hashing uses PBKDF2-HMAC-SHA256 at 120,000 iterations,** built-in crypto only. The recovery code avoids look-alike characters and ignores case and spacing, since it's copied by hand.
- **Changing the PIN keeps the existing recovery code,** because that's the one written down.
- **Debug-only theme hook.** `am start ... --es mbf_theme light|dark|system` sets the night mode for this app alone through `UiModeManager.setApplicationNightMode`. Why: the Pixel 8's system dark mode is locked against `cmd uimode`, and this avoids touching phone settings. It is compiled out of release builds.
- **Unit tests pin Espresso 3.7.0.** Why: the older version Compose's test library brings in calls a hidden method removed in API 37.
- **Unused icons are exempt from lint until Phase 8.** See the open items in `docs/PROGRESS.md`.

## 6 Nov 2026: members and payments (Phase 4)

- **Home's four tabs are equal-width cells, with the count above the label,** rather than a scrolling row. Why: on the phone, "Frozen/Left" was cut off at the screen edge, and all four counts should always be visible.
- **Save on Record payment waits for the figures to refresh.** Why: changing tab or plan works the amount out again in the background, and a fast tap could have saved a stale amount. Only the latest refresh counts.
- **Moving forward to a new screen dismisses the snackbar,** and Add member lifts clear of it. Why: it could sit over a bottom button for its full 5 seconds, which got in the way of adding several members in a row. The cost is that Undo is gone once you navigate forward; a payment can still be deleted from the member's history.
- **Extended floating buttons set their own TalkBack description.** Why: Material hides their visible label from accessibility, so "Add member" was silent to TalkBack.
- **Show QR only appears when a UPI ID is set.**
- **Search also looks across all tabs.** Why: when bhaiya searches a name, the member should appear wherever they are. The tabs hide while searching.
- **The QR screen is always black on white,** whatever the phone's mode, so any camera can read it.
- **Aadhaar typing and reveal block screenshots.** While digits are in the field, or the number is revealed, the window uses `FLAG_SECURE`. Leaving the app hides a revealed number at once.
- **Debug-only hooks live in `src/debug`** (theme, seeding fake members, setting a UPI ID), with an empty twin in `src/release`, so none of it can ship.
- **`App` is an open class with overridable factories.** Why: UI tests run the real screens against an in-memory database, using a `TestApp` subclass.
- **"Send SMS" on Member detail and on multi-select arrives with Phase 5,** along with the messaging engine.
- **Testing typed the fake Aadhaar as key presses.** Why: `adb shell input text` puts its argument in the system log, which looked like a leak but wasn't the app. The test uses a made-up number from UIDAI's 9999 test range.
