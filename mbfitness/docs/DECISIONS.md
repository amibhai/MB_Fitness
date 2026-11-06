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

## 6 Nov 2026: SMS and scheduling (Phase 5)

- **Three tries in all, 5 and then 30 minutes apart.** SPEC 8.5 lists three waits (5 min, 30 min, 2 h) but also "max 3 attempts", which only leaves room for two waits. docs/RULES.md says to pick the safer reading when the spec is ambiguous, and a fourth copy of a message is the worse mistake. A failed message stays visible in Messages with Retry, so nothing is lost. To use the 2-hour wait instead, add it back in `RetryPolicy`.
- **Pacing counts every try that reaches the radio, in a new `send_attempts` table, kept for 2 days.** Why: counting only messages that went would let a whole queue hit the radio at once with no signal, because each failure freed up its slot. That breaks hard rule 3, and Android's own 30-in-30-minutes check counts tries too. A try that was blocked before reaching the radio (no permission, no number) isn't counted.
- **A clock moved backwards can't freeze sending.** Tries with a time in the future are ignored when pacing.
- **A daily run that couldn't plan doesn't count as a run.** It doesn't set `lastDailyRunDay` and posts no summary. Why: otherwise the watchdog wouldn't try again, Home would stay green and the "reminders stopped" notice could never fire, which is the silent failure SPEC 9 exists to prevent.
- **Retrying an old reminder plans it afresh.** Why: a reminder that failed on Monday says "due today", which is false by Thursday, and SPEC 8.2 says every SMS stays truthful. If the member has since paid, or nothing is due, nothing goes and the screen says so. Thank-yous, manual SMS and broadcasts stay true, so they're retried as written.
- **Late results still count.** A row given up on after 10 minutes ("may have gone") turns to Sent or Delivered if the result or delivery report arrives after all, so bhaiya isn't led into sending it twice. A delivery report that overtakes the sent result also ends as Delivered.
- **A crash before the radio doesn't use up a try,** and neither does a block.
- **A message cancelled by hand stays cancelled.** Why: found on the phone. Cancelling used to clear the duplicate key, so the planner sent the same reminder again at the next run. The key now stays, and only that message is stopped: later reminders in the schedule still go. Replacing a stale reminder still clears the key, because its replacement needs it.
- **Two WorkManager names for the outbox: `outbox-now` (APPEND_OR_REPLACE, as SPEC 9 says) and `outbox-later` (REPLACE, delayed).** Why: with one name, a wake-up for tomorrow morning would make a message written now wait behind it. The engine's lock stops the two from ever sending at once.
- **The sent receiver starts the outbox instead of setting its own wake-up,** because only the outbox can see the whole queue and pick the right time.
- **Test mode is read before every message,** so turning it on takes effect from the very next one.
- **The morning summary says "on their way", not "sent".** SPEC 8.10 says "12 reminders sent", but the summary posts right after planning, before the outbox has finished, so "sent" could be false.
- **Delivery status comes from the result code alone.** Why: the PendingIntents are immutable (SPEC 8.5), so the radio can't attach the delivery PDU.
- **SIM-missing notices wait for Phase 6,** along with the SIM picker in Settings. Until a SIM is chosen, the phone's default is used.
- **Each template only allows the placeholders it can fill.** Why: `{receipt_no}` on a reminder has no value, so the planner would hold that SMS back forever. `TemplateRules.placeholdersFor` is stricter than the spec's single list. An `{aadhaar}` placeholder is refused everywhere.
- **Switching language keeps what's on or off and when things send.** Reset puts everything back as it came. Why: moving to Hinglish shouldn't undo a changed schedule, but Reset should mean reset.
- **The gym's name is added to manual SMS and broadcasts if it isn't already there,** with a preview showing the final text. Why: SPEC 8.9 wants the gym's name in every message.
- **Broadcasts can be cancelled as a group, and retried as a group.** Why: stopping a mistaken 50-member broadcast shouldn't take 50 taps. The spec only asks for Retry and Cancel in general.
- **Messages opens on the Failed tab if anything has failed.** Why: SPEC 8.10 sends a failure notification to Messages, and that's what needs looking at.
- **Unsaved template edits survive switching between templates,** and leaving the screen asks first. Why: never lose data.
- **Member detail's buttons became Call and Send SMS on one row, with Freeze below.** Why: three on one row wrapped "Unfreeze" mid-word.
- **The owner's number is set by a debug hook until Phase 6 builds Settings → Gym.** `am start ... --es mbf_trainer <number>`. It only exists in debug builds.
- **Debug-only text size hook.** `am start ... --ef mbf_font 1.3` sets the text size for this app alone. Why: changing the phone's own font affects whoever is holding it, and a dropped USB connection could leave it stuck. `MainActivity.attachBaseContext` calls it, and in a release build that does nothing.

## 6 Nov 2026: onboarding, settings, safety (Phase 6)

- **Backups hold the entities as they are, not a separate copy of each table's fields.** Why: a column added later can't be silently left out of backups.
- **The error log and the send log stay out of backups.** SPEC 12 says "all tables", but these describe this phone, not the gym's data. The send log also has to keep counting what this phone really sent.
- **The file has a date, not a timestamp.** Why: SPEC 15 wants a byte-identical re-export. That holds for a no-password backup. A password backup uses a fresh IV each time, as AES-GCM must, so its bytes differ while the data inside matches. The test compares the data.
- **A password backup is never replaced by a plain one.** If the stored key can't be reached, the backup stops and asks for the password again. Why: falling back would quietly undo what the password was for.
- **SIM, PIN, Test mode and the backup folder are not restored.** They belong to the phone, and restoring Test mode off could start real SMS without anyone asking.
- **Nothing that was waiting to send survives a restore, and its duplicate key stays.** Why: a weekly backup is taken just as the day's reminders are queued, so those rows are in the file as "queued" although they were sent minutes later. Sending them again after a restore would text everyone twice.
- **After a restore, nothing runs until tomorrow.** Why: the backup can't show what went out after it was made, and this gives time to look things over. Tomorrow's run catches up, with one truthful SMS per member.
- **The safety copy before a restore is always kept privately, with Aadhaar sealed,** and also goes to the backup folder if there is one, protected as backups normally are. A broken folder doesn't block a restore, but a failed private copy does. "Undo the last restore" puts it back.
- **A damaged database file is copied aside and reported.** Why: Android deletes a corrupt database as soon as it notices and starts an empty one, so the app would look like a new install with every member gone, and the spec's `quick_check` at the next start would pass. This needs `androidx.sqlite:sqlite-framework`, at the version Room already uses.
- **Backup overdue means more than 8 days, and Home only mentions it once there are members.**
- **Onboarding's test step leaves Test mode alone.** SPEC 11.1 turns it on and off again, but a test SMS only ever goes to the owner's number, and skipping that step would have left Test mode on, with members silently getting nothing.
- **The restricted-settings guide is numbered steps with an Open App info button, not three pictures.** There are no real screenshots to use, and they'd differ by phone. It can become pictures at handover if needed.
- **A dual-SIM phone with one SIM in it drops the SIM step.** Android only reveals how many SIMs are active after a permission, so the step can appear, then remove itself.
- **Settings groups backup, restore, CSV, folder and password on one screen** rather than as separate rows in the Data section. Why: they're used together and belong near their explanation.
- **Changing or removing the PIN asks for the current one first.** This closes the open item from Phase 3. The fingerprint can answer it too, the same as for showing an Aadhaar number.
- **Exports start with a byte-order mark,** so Excel reads Hindi names properly. Names starting with `=`, `+`, `-` or `@` get an apostrophe, so they can't run as a formula in Excel.
- **A member export has the import file's columns first,** so it can go straight back in.
- **"Remove old records?" moves members to the recycle bin, not out of the app.** Why: SPEC 12 says never automatic, and this leaves 30 days to change your mind. It comes last among Home's banners.
- **Debug-only hooks for checks on the phone:** a backup folder inside the app's own storage, a member with a made-up Aadhaar number, and skipping onboarding. None of them ship.

## 6 Nov 2026: reports and widget (Phase 7)

- **"Pending" leaves out members who have left, everywhere.** Home counted them and the morning summary didn't, so the two could disagree. Someone who has left isn't being chased. What they owe stays on their own page.
- **New joins are new people, not imports.** An import at go-live shouldn't look like 50 people joining in a day.
- **A member's first renewal is them joining, so it isn't counted as a renewal.** An opening balance means they were already a member, so their next payment is a renewal.
- **A past month's Active and Pending are worked out for the end of that month,** by playing events and payments forward. The balance list at the bottom is always as of now.
- **A member in the recycle bin leaves the reports, and comes back if restored.** Once deleted for good, their history is gone, as SPEC 12 says, so old months can change then.
- **The month switcher goes back to the first month with data,** and stops at the current one.
- **Charts follow the single-series rules:** one colour for all the marks, bars at most 24 dp with only the top corners rounded, a 2 dp line, and no legend because the title says what's plotted. The chart colour (`#C2410C` light, `#E8590C` dark) was checked for contrast against both card colours. Each month is a tap target bigger than its mark, and TalkBack reads each month's value, so nothing depends on seeing the chart.
- **The active-members line doesn't start at zero.** A gym's count moves by a few people, which would look flat from zero. The picked month's exact number shows above the chart, so the scale can't mislead.
- **One resizable widget instead of two.** It shows counts when small, and adds pending and names when taller. Why: less for bhaiya to choose between, and the same result.
- **With an app PIN set, the widget only shows counts.** SPEC 4-E puts names and money on it, but anyone can see a home screen without getting past the lock, and a PIN means that person asked for this to stay private.
- **The widget follows the data rather than being told.** While the app is open it watches members and payments, so no path can forget to refresh it, whether a payment, an undo, an import or a restore. The daily run also refreshes it, in case the day changed things.
