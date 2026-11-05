# MB Fitness Club — Master Build Spec (final, v3)

> Owner: **Jeetu Bhaiya**, MB Fitness Club · App by **Swastik** · Spec date: 4 Nov 2026
> This is the single source of truth. `docs/RULES.md` holds the working rules and points here.

---

## 0. How to use this spec

- Put the project in the repo root, the working rules at `docs/RULES.md` and this file at `docs/SPEC.md`.
- Build **phase by phase** (§17). Each phase has a brief and a "done when" list. Don't start the next phase until the current one passes `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- If reality disagrees with this spec (an API doesn't exist, a library version doesn't support something), **choose the safer option, keep going, and record it in `docs/DECISIONS.md`** (date, what, why). Where `docs/DECISIONS.md` names a deliberate deviation, it overrides the spec text it names.
- Everything in §4 ships in **Release 1.0**. There is no "v2" — bhaiya should install one finished app and never need an update.

---

## 1. Goals and non-negotiables

1. **₹0 running cost.** No servers, subscriptions, paid SDKs, API keys or accounts. The only cost is SMS on his existing prepaid plan.
2. **Fully offline.** The app has **no `INTERNET` permission**.
3. **Build once, run for years.** Everything changeable is a setting. Reminders survive reboots, OS updates, SIM swaps and missed days.
4. **Never lose or corrupt money data.** Every money change is transactional, reversible (undo/edit history), and backed up weekly.
5. **Never get his SIM blocked.** Paced sending, consent, opt-out, service-only messages, no links (§8.9).
6. **Simple for bhaiya.** Daily actions take ≤ 3 taps. Big, readable text. No jargon.
7. **Beautiful.** Calm, warm, modern (§10.1). It should feel like a premium app, not a spreadsheet.

**Success looks like:** after setup, bhaiya opens the app only to record payments and glance at the morning summary. Reminders go out by themselves every day for years, nobody complains about spam, and no payment record is ever lost.

---

## 2. Facts this plan is built on (researched Nov 2026)

| Fact | What the app does about it |
|---|---|
| Android warns/blocks when an app sends more than **30 SMS in 30 minutes** (`sms_outgoing_check_max_count` = 30, interval 30 min). Beyond that it shows a confirm dialog, which breaks automation. | All SMS go through a paced **outbox**: at most **25 SMS parts per rolling 30 minutes**, 3–5 s apart (§8.4). |
| "Restricted settings" (introduced in Android 13, extended in Android 15 to cover the **SMS permission**) blocks it for apps installed from a browser, messaging app or file manager, until the user taps **App info → ⋮ → Allow restricted settings**. | Install over **USB (ADB)** from the laptop (§16). Onboarding detects a blocked SMS permission and walks bhaiya through "Allow restricted settings" with pictures (§11). |
| Google Play Protect's India pilot blocks internet-sideloaded apps that request **RECEIVE_SMS, READ_SMS**, notification-listener or accessibility. | The app requests **only `SEND_SMS`**. Never add RECEIVE_SMS, READ_SMS, accessibility or notification-listener permissions. |
| **Android developer verification:** enforcement starts 30 Sep 2026 in Brazil, Indonesia, Singapore and Thailand; **global rollout in 2027**. ADB installs are exempt. A **free "limited distribution" account** allows up to 20 devices without ID or fee. Registration is per package name + signing key; **a lost key means the package can't be registered.** | Package `com.swastik.mbfitness`, one release key, backed up in 2 places. Register the package in a free limited-distribution account **before 2027** so future installs/updates on bhaiya's phone keep working (§16). |
| Android 14+: `SCHEDULE_EXACT_ALARM` is **denied by default** for new installs. Inexact alarms and WorkManager don't need it. | Ask for "Alarms & reminders" during onboarding. If granted, use an exact alarm; if not, fall back to `setWindow()` (≥10 min window) plus a WorkManager watchdog (§9). |
| Android 17 (API 37, released 16 Jun 2026) adds a **memory limiter** that kills processes exceeding RAM limits; exits show `MemoryLimiter:AnonSwap`. | The daily job is small and streams from the DB. R8 full mode. Health check records the last `ApplicationExitInfo` reason (§13). |
| Xiaomi/Redmi/POCO, Realme, Oppo, Vivo, Samsung aggressively kill background apps (needs Autostart, "No restrictions" battery, lock in recents). | Brand-specific onboarding steps + "reminders last ran" + watchdog that catches up on app open (§9, §11). |
| **TRAI:** anyone sending commercial messages from a 10-digit number is treated as an **Unregistered Telemarketer**; spam = messages **without the recipient's consent**. After complaints: 1st = warning, 2nd = **usage cap for 6 months**, 3rd+ = **disconnection up to 2 years + blacklisting**. | Fee reminders are service messages to paying members **who agreed at joining**. Record SMS consent per member, one-tap opt-out, no promotional blasts to ex-members, **no links**, low daily volume (§8.9). |
| Typical Jio prepaid plans include **100 SMS/day**; beyond that, SMS are charged. | Default daily cap **90 parts**. Onboarding asks bhaiya to confirm his plan includes SMS (§11). |
| **DPDP Rules** notified 13 Nov 2025; most duties (notice/consent, security safeguards, breach handling, erasure, minors' consent) apply from **13 May 2027**, with **no small-business exemption**. | Data stays on the phone, app PIN, consent note at joining, delete-on-request, minors flagged for guardian consent, encrypted backups optional (§12). |
| **Aadhaar (Sharing of Information) Regulations 2016**, regs 5 and 6: anyone collecting an Aadhaar number needs a lawful purpose, must tell the holder the purpose and whether it is mandatory or voluntary, must get consent, must not use it for anything else or share it without consent, must keep it secure and confidential, must not display it publicly, and must not keep it longer than needed. UIDAI announced in Dec 2025 that it will bar private entities from storing **copies** of the card. | Optional field with its own consent tick, encrypted at rest, masked on screen, PIN to reveal, never in SMS or CSV, removable any time, no card photos (§7.10). |
| UPI deep link: `upi://pay?pa=<vpa>&pn=<name>&am=<amount>&cu=INR&tn=<note>` (amount with 2 decimals, parameters URL-encoded). | Offline **UPI QR** with the exact amount on screen (§4-B, §10.2 screen 6). |
| `SmsManager.createForSubscriptionId(subId)` (API 31+) picks the SIM without the disambiguation dialog. | SIM choice in Settings; fallback to default SMS SIM (§8.5). |

References are listed in §19.

---

## 3. The people and their day

**Jeetu Bhaiya (only user).** Busy, on his feet, phone in hand between sets. Comfortable with WhatsApp and UPI, not with settings screens.

| When | What he does | How many taps |
|---|---|---|
| 9:00 AM | Reads the morning summary notification ("3 due · 4 overdue · 12 reminders sent") | 0–1 |
| Member pays | Search name → **Record payment** → (optional) **Show UPI QR** → Save | 3–4 |
| New joiner | **+ Add member** → name, number, plan, consent tick → Save | 4–5 |
| Evening | **Today's closing**: cash vs UPI collected today | 1 |
| Festival | Settings → Quiet days → add date; or Broadcast a "closed tomorrow" SMS | 4–5 |
| Month end | Reports | 1 |

Everything else (reminders, backups, retries, catch-up) happens on its own.

---

## 4. Release 1.0 feature list (everything ships together)

Items marked **[new]** were added in this final plan for safety or convenience.

**A. Members**
- Add (manual or **from contacts** via the system contact picker), edit, search (name or any digits of the phone), status tabs: **Overdue · Due soon · Paid · Frozen/Left**.
- Freeze / unfreeze (expiry extends by frozen days). Mark as Left / Rejoin. Permanent delete (type name to confirm) → goes to **Recycle bin for 30 days [new]**.
- Multi-select (long-press): Mark as Left, Send SMS (opens Broadcast with those members).
- 60+ days overdue → suggestion banner to mark as Left (never automatic).
- Per member: **SMS consent [new]**, reminders on/off, date of birth (optional), notes, **"under 18" guardian name/phone [new]**.
- **Aadhaar number [new]** on every member: optional, with its own consent tick. Encrypted on the phone, shown masked as `XXXX XXXX 1234`, full number only after PIN or fingerprint (§7.10).
- Import: from contacts, or **CSV** (sample file, preview, per-row errors, duplicates skipped).

**B. Payments**
- **Renewal** (pick plan; amount editable) or **Pay balance**. **Partial payments** create a balance.
- Payment modes: UPI, Cash, Other.
- **Receipt numbers [new]**: `MBF-0001`, sequential, never reused.
- **Edit or delete** a payment with **Undo** and an **edit history [new]** (old values kept for disputes). Expiry and balance recompute from scratch.
- Renewal rule (setting): **A. continue from old expiry** (default) or **B. start from payment date**; members away > 60 days restart from today.
- **Show UPI QR [new]**: full-screen QR with bhaiya's UPI ID and the exact amount; works offline.
- **Today's closing [new]**: today's cash vs UPI vs other, list of payments, total.

**C. Automatic reminders (SMS)**
- Templates: BEFORE_DUE (−3 days), ON_DUE (0), OVERDUE_1 (+3), OVERDUE_2 (+7, then weekly, max 4), BALANCE_DUE (7 days after a part payment, weekly, max 3), PAYMENT_THANKS (after a payment), BIRTHDAY.
- Fully editable templates with placeholders, live preview, SMS-part counter, on/off and day offsets; **English and Hinglish preset packs [new]** ("Reset to English / Hinglish").
- One SMS per member per day max; dedupe per expiry; daily cap; **pacing under Android's 30-in-30-minutes limit [new]**; retries; catch-up after missed days.
- **Quiet days**: weekly days off (default Sunday) + dated holidays; skipped reminders go out on the next normal day.
- **Morning summary notification** (due, overdue, balance, sent, failed, birthdays, quiet-day notice).
- **Test mode [new]**: all SMS go only to bhaiya's own number (prefixed "[TEST → Rahul]") — for setup and demos.

**D. Broadcast SMS**
- Audiences: All active · Overdue · Due soon · Balance pending · Frozen · Hand-picked. (Left members only as hand-picked, with a spam warning — see §8.9.)
- Preview, part counter, recipients × parts vs today's remaining limit; sends in the background with pacing.

**E. Reports & numbers**
- This month: collected (vs last month ↑/↓ %), pending, active, new joins, left, renewals, average per member.
- 6-month collection bars, 6-month active-members line, list of members with balances.
- **Home-screen widget** (small: "3 due · 4 overdue"; medium: + ₹ pending + next 3 overdue names).

**F. Branding & look**
- Gym logo (picked from gallery, saved inside the app), "**JAI SHREE RAM**" greeting + "**Jeetu Bhaiya**" on Home and splash, "App by Swastik" on splash and About. Plus Jakarta Sans font bundled. Warm, calm visual design (§10.1). Light + dark mode.

**G. Safety & data**
- Automatic weekly backup to a folder he chooses (keeps last 8), manual export/import (JSON), CSV export, Android auto-backup, **optional backup password [new]**.
- App lock: PIN and/or fingerprint/face; **recovery code [new]**.
- Health check screen with one-tap fixes; local error log.
- **Help screen [new]**: 6 short illustrated how-tos (record payment, add member, quiet day, broadcast, backup, "reminders stopped?").

**Not in 1.0 (deliberately):** WhatsApp sending, attendance, diet/workout plans, staff logins, cloud sync, online payments, GST invoices, Hindi UI (templates can be Hindi/Hinglish already). See §18.

---

## 5. Tech stack and architecture

### 5.1 Stack
- **Kotlin**, **Jetpack Compose** (Material 3), single activity, Navigation Compose.
- **Room** (with KSP), **DataStore (Preferences)**, **WorkManager**, **AlarmManager**.
- **Glance** (widget), **androidx.biometric**, **androidx.core:core-splashscreen**, **kotlinx.serialization** (backup JSON), **kotlinx.coroutines**.
- **ZXing core** (Apache-2.0) for QR generation only.
- Tests: JUnit 4/5, kotlinx-coroutines-test, Room in-memory tests (instrumented or Robolectric), Compose UI tests for key flows.
- `compileSdk` / `targetSdk`: **37** (Android 17). If the installed toolchain can't support 37, use 36 and note it in DECISIONS.md. `minSdk` **26**.
- Pin every version in `gradle/libs.versions.toml` (latest stable at build time). No `+` or dynamic versions.
- R8 enabled for release (full mode), resource shrinking on.

### 5.2 Banned
The `INTERNET` permission (from our manifest *or* any merged library manifest) · Firebase / Google Play Services / Crashlytics / analytics / ads · any SDK needing an API key, account or network · RECEIVE_SMS / READ_SMS / READ_CALL_LOG / accessibility / notification listener / `USE_EXACT_ALARM` · Google "downloadable fonts" · chart libraries (draw with Compose Canvas) · `fallbackToDestructiveMigration` · storing the PIN in plain text.

**Final check in Phase 8:** the merged manifest contains only the permissions in §11.2, and `./gradlew :app:dependencies` contains nothing banned.

### 5.3 Project layout (single `app` module)
```
com.swastik.mbfitness
├─ domain/          ← PURE Kotlin, no Android imports. 100% of the business rules live here.
│   ├─ Dates.kt               (epoch-day helpers, addMonths, IST "today" provider interface)
│   ├─ MemberRules.kt         (status, recalculateMember, balance)
│   ├─ ReminderPlanner.kt     (who gets what today — deterministic)
│   ├─ QuietDays.kt
│   ├─ TemplateEngine.kt      (placeholders, conditional balance line)
│   ├─ SmsSegments.kt         (GSM-7 vs Unicode part counting)
│   ├─ Pacing.kt              (daily cap + rolling 30-min window maths)
│   ├─ PhoneNumbers.kt, Aadhaar.kt, CsvParser.kt, UpiLink.kt, ReceiptNumbers.kt
├─ data/            ← Room entities, DAOs, AppDatabase, migrations, Repository, DataStore settings, AadhaarVault
├─ sms/             ← SmsSender interface, SimSmsSender, TestModeSender, FakeSmsSender (tests), SentReceiver, DeliveredReceiver
├─ work/            ← DailyAlarmReceiver, BootReceiver, DailyRunWorker, OutboxWorker, WatchdogWorker, BackupWorker
├─ backup/          ← JSON export/import, CSV export/import, optional encryption
├─ ui/              ← theme/, components/, screens/ (one package per screen), navigation
├─ widget/          ← Glance widgets
└─ App.kt           ← manual DI container (no Hilt/Koin)
```
Rules: UI → ViewModel → Repository → DAO. ViewModels expose `StateFlow<UiState>`. **All business decisions are made in `domain/` and unit-tested.** The clock is injected (`TodayProvider`) so tests can set any date.

---

## 6. Data model

All dates are **epoch days** (`Long`, `LocalDate.toEpochDay()`), never timestamps, except `…At` audit fields (epoch millis). Money is **whole rupees as `Long`** (no floats). The database includes every table from day one; `exportSchema = true` with schema JSON committed; any later change ships a real `Migration` plus a migration test.

```
Plan          id, name, months (1–24), price, isActive, sortOrder
Member        id, name, phone (E.164 "+91XXXXXXXXXX", UNIQUE among non-deleted),
              joinDate, planId, expiryDate (cached), status (ACTIVE|FROZEN|LEFT),
              dob?, notes, smsConsent (bool), consentDate?, remindersEnabled (bool),
              isMinor (bool), guardianName?, guardianPhone?,
              aadhaarEnc? (AES-256-GCM ciphertext, never plaintext), aadhaarLast4?, aadhaarConsentDate?,
              createdAt, updatedAt, deletedAt? (recycle bin)
Payment       id, receiptNo (UNIQUE, e.g. 17 → "MBF-0017"), memberId, kind (RENEWAL|BALANCE|OPENING),
              charged, paid, paidOn, mode (UPI|CASH|OTHER), planId?, periodStart?, periodEnd?,
              note, createdAt, editedAt?, editHistory (JSON list of previous versions), deletedAt?
Freeze        id, memberId, fromDate, toDate? (null = still frozen)
MemberEvent   id, memberId, type (JOINED|LEFT|REJOINED|FROZEN|UNFROZEN|DELETED|RESTORED), date
Message       id, memberId?, phone, kind (BEFORE_DUE|ON_DUE|OVERDUE_1|OVERDUE_2|BALANCE_DUE|
              PAYMENT_THANKS|BIRTHDAY|BROADCAST|MANUAL|TEST), body, parts, priority (1 high … 5 low),
              status (QUEUED|SENDING|SENT|DELIVERED|FAILED|CANCELLED), forExpiry? (dedupe key),
              broadcastId?, createdAt, notBefore, sentAt?, deliveredAt?, attempts, lastError?
Template      type (PK), body, enabled, offsetDays, repeatEveryDays?, maxRepeats?
Broadcast     id, body, audience, createdAt, recipientCount
QuietDate     id, date, label
ErrorLog      id, at, tag, message   (keep newest 200)
```
Indexes: `Member(status)`, `Member(expiryDate)`, `Payment(memberId, paidOn)`, `Message(status, notBefore)`, `Message(memberId, kind, forExpiry)`.

**Settings (DataStore):** `gymName` ("MB Fitness Club"), `ownerName` ("Jeetu Bhaiya"), `greeting` ("JAI SHREE RAM"), `trainerPhone`, `upiId`, `upiPayeeName`, `logoFile`, `reminderTime` (09:00), `dailyCapParts` (90), `windowCapParts` (25 per 30 min), `simSubscriptionId?`, `renewalRule` (A), `restartAfterDays` (60), `quietWeekdays` ({SUNDAY}), `morningSummary` (on), `allClearNotice` (on), `testMode` (off), `pinHash` + `pinSalt` (PBKDF2), `biometric` (off), `recoveryCodeHash`, `backupFolderUri`, `backupPasswordSet`, `lastBackupDay`, `lastDailyRunDay`, `lastDailyRunAt`, `onboardingDone`, `nextReceiptNo`.

---

## 7. Core rules (all in `domain/`, all unit-tested)

### 7.1 Dates
- "Today" = `LocalDate.now(ZoneId.of("Asia/Kolkata"))` via `TodayProvider` (device zone fallback is a bug source; India never changes zone).
- `addMonths(day, n)` clamps to month end: 31 Jan + 1 → 28/29 Feb; 30 Nov + 3 → 28/29 Feb.
- Tests must cover: 29 Feb, 31 Dec → Jan, 31 Jan + 1, 31 Mar + 1, renewals spanning a leap year.

### 7.2 Member status (derived, never stored)
| Status | Rule |
|---|---|
| **Overdue** | ACTIVE and today > expiry |
| **Due soon** | ACTIVE and 0 ≤ expiry − today ≤ 3 |
| **Paid** | ACTIVE and expiry − today > 3 |
| **Frozen / Left** | status FROZEN or LEFT |
| Badge "₹X due" | balance > 0 (any tab) |

`balance = Σ charged − Σ paid` over non-deleted payments (can't go below 0 in display; overpayment shows "₹X advance").

### 7.3 Recording a payment
1. **Renewal:** plan (default current) → `charged = plan.price` → amount received (default = price + old balance, editable) → mode → Save.
   - New period start: rule A = old expiry; rule B = payment date; if today − old expiry > `restartAfterDays` or member was LEFT → today.
   - `periodEnd = addMonths(periodStart, plan.months)`.
   - Show before saving: new expiry, and "₹700 will remain due" / "Fully paid" / "₹200 extra — check amount".
2. **Pay balance:** amount (default = balance). Expiry unchanged.
3. Save = **one Room transaction**: insert payment (next receipt no.) → `recalculateMember` → write MemberEvent if status changed → enqueue PAYMENT_THANKS (if enabled and consent) → commit.
4. Snackbar: "₹1,200 saved · Receipt MBF-0042" with **Show QR** (if UPI) and **Undo** (5 s).

### 7.4 `recalculateMember(id)` — single source of truth
Replay **from scratch** in `paidOn` order: start from `joinDate`; apply each RENEWAL/OPENING payment by the renewal rule; add the length of each completed Freeze overlapping the active period; result → `expiryDate`. Called after any payment add/edit/delete/restore and any freeze change. Never patch incrementally. Property test: random sequences of payments/edits/deletes give the same result as a fresh replay.

### 7.5 Edit, delete, undo
- Edit any field; previous version appended to `editHistory` with time; row shows "edited". Delete = soft delete (`deletedAt`), Undo restores. Confirmation shows the effect ("Rahul's expiry changes from 12 Dec to 12 Nov").
- Soft-deleted payments are purged after 30 days.

### 7.6 Freeze
Freeze → `Freeze(from = today)`, status FROZEN, no reminders. Unfreeze → `to = today`, status ACTIVE, recalc (expiry extends by frozen days). Max one open freeze per member.

### 7.7 Left, rejoin, delete, recycle bin
- **Mark as Left:** status LEFT, reminders stop, history kept. **Rejoin:** ACTIVE + opens Record payment (period starts today).
- **Delete permanently:** type the member's name → `deletedAt` set, hidden everywhere → **Recycle bin** (Settings) for 30 days with Restore → then purged with payments, freezes, messages.
- Multi-select has no bulk delete.

### 7.8 Phone numbers
Normalize: strip spaces, dashes, brackets, leading `0`, `+91`/`91` prefix → must be 10 digits starting 6–9 → store `+91…`. Display "98765 43210". Unique among non-deleted members (on clash, show the existing member).

### 7.9 Import (Settings → Import members)
- **Contacts:** system contact picker (`ACTION_PICK` / Android 17 contact picker); no `READ_CONTACTS` permission unless multi-pick needs it, requested only there.
- **CSV:** "Get sample file" writes `mbfitness_import_sample.csv` with `name,phone,plan_months,expiry_date(DD-MM-YYYY),balance_due,dob(DD-MM-YYYY, optional),notes,aadhaar(optional)`. Own RFC-4180 parser (quotes, commas in names, BOM, CRLF). Preview: green OK rows, red rows with reasons, duplicates listed. Confirm → each member gets one OPENING payment so recalculation stays consistent. Imported members get `smsConsent = true` only if bhaiya ticks "These members have agreed to SMS reminders". The `aadhaar` column is validated per row (§7.10), with a specific error when Excel has damaged it (`1.23457E+11`), and is only imported if bhaiya ticks a separate box, "These members agreed to share Aadhaar for gym records"; without that tick the column is ignored. The sample file leaves it blank, and the preview reminds him to delete the CSV after importing.

### 7.10 Aadhaar number (added 5 Nov 2026 at Swastik's request)
Optional on every member, because no law requires it for a gym and regulation 5 requires a legal basis to make it mandatory. The app only records the number. It does not verify it with UIDAI and never stores a photo of the card.
- **Validation** (`domain/Aadhaar.kt`): 12 digits, first digit 2-9, valid Verhoeff check digit. Spaces and dashes are ignored. Error: "Aadhaar number is not valid. Please check the digits." A value object masks itself in `toString()` so it can't leak through logging.
- **Consent:** entering a number reveals a separate, unticked box: "Member agreed to share Aadhaar for gym records. It is optional." ("Guardian agreed..." for under-18s.) Save is blocked until it is ticked. The date goes in `aadhaarConsentDate`.
- **Storage:** `aadhaarEnc` holds AES-256-GCM ciphertext under a non-exportable Android Keystore key (`data/AadhaarVault.kt`, built-in APIs only). `aadhaarLast4` is plain. There is no plaintext column. If the key is missing or fails (for example after a restore onto a new phone), the last 4 digits stay visible with "Full number not available on this phone. Re-enter it." This never crashes and never blocks saving the member.
- **Viewing:** always shown as `XXXX XXXX 1234`. "Show" needs the app PIN or fingerprint, reveals the number for 30 seconds with screenshots blocked (`FLAG_SECURE`), and offers no copy button. With no PIN set, "Show" asks for one to be set first; saving the first Aadhaar also prompts for a PIN.
- **Duplicates:** the same number on another non-deleted member shows a warning naming that member, with "Save anyway". Candidates are found by `aadhaarLast4`, then decrypted and compared.
- **Never appears in:** SMS templates or broadcasts (`{aadhaar}` is an unknown placeholder and is rejected), search, notifications, widget, error log, logcat, CSV export, Today's closing share text.
- **Removal:** "Remove Aadhaar" on Member detail; an unticked "Also remove Aadhaar" in the Mark as Left dialog; erased with the member when the recycle bin purges.

---

## 8. Messaging engine

### 8.1 Outbox (one pipeline for every SMS)
Every SMS — reminders, thank-yous, broadcasts, manual, test — is a `Message` row. Nothing calls `SmsManager` except `OutboxWorker`. This gives one place for pacing, quiet days, retries, logging and test mode.

### 8.2 Daily planner (`ReminderPlanner`, pure function)
Input: today, members (+ balances, payments, freezes), templates, message history, quiet days, settings. Output: list of messages to enqueue. Deterministic and idempotent (running twice the same day enqueues nothing new).

| Template | Fires when (ACTIVE, consent, reminders on) | Dedupe key |
|---|---|---|
| BEFORE_DUE | today == expiry − 3 | (member, kind, expiry) |
| ON_DUE | today == expiry | (member, kind, expiry) |
| OVERDUE_1 | today == expiry + 3 | (member, kind, expiry) |
| OVERDUE_2 | today == expiry + 7, then every 7 days, max 4 | (member, kind, expiry, n) |
| BALANCE_DUE | balance > 0, not expired, 7 days after the part payment, weekly, max 3 | (member, kind, paymentId, n) |
| BIRTHDAY | today == dob (29 Feb → 28 Feb in non-leap years), once a year | (member, kind, year) |

Rules:
- **One automatic SMS per member per day.** Priority if several apply: OVERDUE_2 > OVERDUE_1 > ON_DUE > BEFORE_DUE > BALANCE_DUE > BIRTHDAY. Expiry reminders already include the balance in `{amount}`.
- **Catch-up after missed days / quiet days:** per member, take the **latest schedule point ≤ today that hasn't been sent** for the current expiry. If its wording would be false today, use the next template that is true (BEFORE_DUE on/after expiry day → ON_DUE; ON_DUE after expiry day → OVERDUE_1 with `{days_overdue}` = actual days). Earlier unsent points are marked superseded so they never go later. Result: **at most one message per member, always truthful**, never a burst of old ones. (Example: expiry falls on a quiet Sunday → Monday sends OVERDUE_1 saying "1 day overdue".)
- Day offsets and repeats come from the Template rows (editable).

### 8.3 Quiet days
- Weekly weekdays (default Sunday) + dated holidays with names. Past dates hidden; purged 30 days later. Festival dates are **never hardcoded** — bhaiya adds them.
- On a quiet day the planner enqueues nothing automatic; still runs housekeeping (watchdog, backup, pruning) and posts "Quiet day (Diwali) · no reminders · 6 will go tomorrow".
- Still allowed on quiet days: PAYMENT_THANKS, MANUAL, his own broadcasts (with a "today is a quiet day" note), BIRTHDAY.
- "Run reminders now" on a quiet day asks for confirmation.

### 8.4 Limits and pacing (`Pacing.kt`)
- **Rolling window:** ≤ `windowCapParts` (25) parts per 30 minutes — safely under Android's 30-in-30-min check.
- **Daily cap:** ≤ `dailyCapParts` (90) parts per IST day (prepaid plans ~100/day).
- **Spacing:** 3–5 s between messages (randomized), so the carrier sees normal human-like traffic.
- When a limit is hit, remaining messages keep `QUEUED` with `notBefore` = next allowed time; `OutboxWorker` re-schedules itself. Daily leftovers carry to tomorrow morning ahead of new reminders.
- Priority order inside the queue: PAYMENT_THANKS & MANUAL & TEST (1) → overdue (2) → due (3) → before-due/balance (4) → broadcast/birthday (5).
- Broadcast preview shows the plan in words: "50 members × 1 part. 25 go now, 25 more at 10:05 AM".

### 8.5 Sending
- `SmsManager` from `context.getSystemService(SmsManager::class.java)`, then `.createForSubscriptionId(subId)` for the chosen SIM (API 31+; on 26–30 use `getSmsManagerForSubscriptionId`). Missing SIM → default SMS subscription + warning in summary/health check.
- `divideMessage` + `sendMultipartTextMessage` with sent and delivered `PendingIntent`s (`FLAG_IMMUTABLE`, unique request codes, explicit broadcast receivers registered in the manifest with `exported=false`).
- Result handling: `RESULT_OK` → SENT; delivery report → DELIVERED (not all carriers send these — never treat "no delivery report" as failure). `RESULT_ERROR_NO_SERVICE`, `RADIO_OFF`, `NULL_PDU`, `GENERIC_FAILURE` → retry with backoff (5 min, 30 min, 2 h), max 3 attempts → FAILED (visible in Messages with Retry).
- Mark SENDING before calling the API; on app restart, SENDING rows older than 10 minutes become QUEUED again — but only if no sent-result was recorded (avoid double sends).
- Airplane mode / no signal → messages wait; nothing is lost.

### 8.6 Templates
Placeholders: `{name}` (first name) `{full_name}` `{amount}` `{balance}` `{plan}` `{due_date}` `{days_overdue}` `{gym_name}` `{upi_id}` `{trainer_phone}` `{receipt_no}`.
- `{amount}` = renewal fee + outstanding balance (reminders) or amount just received (PAYMENT_THANKS).
- `{days_overdue}` includes its unit and pluralises: English "1 day" / "3 days"; Hinglish "1 din" / "3 din" (the pack sets the language).
- Sentences inside `[[…]]` are dropped when they contain a zero `{balance}` (e.g. `[[Balance due: Rs {balance}.]]`).
- Unknown placeholder → editor shows an error and won't save.

**English pack (default):**
```
BEFORE_DUE     Hi {name}, your {gym_name} membership expires on {due_date}. Fee: Rs {amount}. UPI: {upi_id}. Thank you!
ON_DUE         Hi {name}, your {gym_name} fee of Rs {amount} is due today. UPI: {upi_id}. Please ignore if already paid.
OVERDUE_1      Hi {name}, your {gym_name} fee of Rs {amount} is {days_overdue} overdue. Kindly pay soon. UPI: {upi_id}
OVERDUE_2      Hi {name}, reminder: your {gym_name} fee is {days_overdue} pending. Please pay or call {trainer_phone}.
BALANCE_DUE    Hi {name}, Rs {balance} is still pending on your {gym_name} fee. Please pay when you can. UPI: {upi_id}
PAYMENT_THANKS Thanks {name}! Received Rs {amount} (Receipt {receipt_no}). Valid till {due_date}.[[ Balance due: Rs {balance}.]] - {gym_name}
BIRTHDAY       Happy Birthday {name}! Wishing you a strong and healthy year ahead. - {gym_name}
```
**Hinglish pack:**
```
BEFORE_DUE     Namaste {name} ji, aapki {gym_name} membership {due_date} ko khatam ho rahi hai. Fees: Rs {amount}. UPI: {upi_id}. Dhanyavaad!
ON_DUE         Namaste {name} ji, aapki {gym_name} fees Rs {amount} aaj due hai. UPI: {upi_id}. Agar de di hai to ignore karein.
OVERDUE_1      Namaste {name} ji, aapki {gym_name} fees Rs {amount} {days_overdue} se pending hai. Kripya jaldi jama karein. UPI: {upi_id}
OVERDUE_2      {name} ji, aapki {gym_name} fees {days_overdue} se baaki hai. Kripya jama karein ya {trainer_phone} par call karein.
BALANCE_DUE    {name} ji, aapki {gym_name} fees ke Rs {balance} abhi baaki hain. Jab ho sake jama kar dein. UPI: {upi_id}
PAYMENT_THANKS Dhanyavaad {name} ji! Rs {amount} mil gaye (Receipt {receipt_no}). Membership {due_date} tak valid.[[ Baaki: Rs {balance}.]] - {gym_name}
BIRTHDAY       Janamdin ki hardik shubhkamnayein {name} ji! Swasth aur mazboot rahiye. - {gym_name}
```

### 8.7 Content rules (enforced by the editor)
- SMS part counter: GSM-7 = 160 chars single / 153 per part; anything outside GSM-7 (₹, emoji, Devanagari, curly quotes) = Unicode 70 / 67. Show "2 parts · Unicode (₹ used)". Warn above 2 parts; block above 4.
- **No links:** reject `http`, `www.`, `bit.ly`, `.com/` etc. (links from personal numbers get flagged as spam).
- Default to "Rs", not "₹". Auto-replace curly quotes with straight quotes.

### 8.8 Broadcast
Audience chips with live counts → message (placeholders `{name}`, `{gym_name}`, `{trainer_phone}`) → preview for the first recipient → pacing plan → Send. Runs through the outbox. Keeps the last 5 broadcasts as drafts. Members with reminders off still get broadcasts unless excluded; members **without SMS consent never do**.

### 8.9 Staying clear of spam rules (TRAI)
- **Consent at joining:** Add Member has a checkbox "Member agrees to get fee reminders by SMS" that bhaiya ticks after asking the member (**not pre-ticked**, so consent is real), with the date recorded. Unticked → a note "No reminders will be sent to this member" and no automatic SMS (manual "Send SMS now" still asks to confirm).
- **Easy opt-out:** every member has "Stop SMS" (Member detail). If a member says "don't message me", one tap stops everything.
- **Service messages only by default.** Broadcast to **Left** members is not an audience chip; hand-picking a Left member shows: "Messages to ex-members can be reported as spam and get your number blocked. Send anyway?"
- **Low volume, human pacing, no links**, gym name in every message so the receiver knows the sender.
- Suggest a short line in the gym's joining form: "I agree to receive fee reminders by SMS from MB Fitness Club."

### 8.10 Morning summary notification
Posted after each daily run (channel "Daily summary", default importance; `POST_NOTIFICATIONS` on Android 13+).
- Line 1: "Today: 3 due · 4 overdue · ₹2,400 balance pending"
- Line 2: "12 reminders sent" / "⚠ 2 failed — tap to retry" / "Quiet day (Diwali) · 6 will go tomorrow" / "🎂 Birthday: Rahul" (emoji allowed only in notifications).
- Tap → Home (Overdue tab) or Messages if something failed. Separate channel "Problems" (high importance) for: SMS permission lost, SIM missing, reminders didn't run for 2 days, backup overdue.

### 8.11 Test mode
Settings toggle (also forced on in debug builds). Every outgoing SMS goes **only to `trainerPhone`**, prefixed `[TEST → Rahul] `. A red "TEST MODE" ribbon shows on Home. Used during setup, demos and Phase 5 testing.

---

## 9. Scheduling and reliability (reminders must never silently stop)

Three layers, all calling the same idempotent `DailyRun` (guarded by `lastDailyRunDay`):
1. **Alarm:** at `reminderTime` (09:00 IST). If `canScheduleExactAlarms()` → `setExactAndAllowWhileIdle`; else `setWindow(start, 30 min)`. The receiver enqueues an **expedited** `DailyRunWorker` and re-arms tomorrow's alarm.
2. **Watchdog:** `PeriodicWorkRequest` every 6 h (unique, KEEP). If today's run hasn't happened and it's past `reminderTime` → run now.
3. **App open:** same check on every app start/resume.

Re-arm alarms on `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` (direct-boot aware receiver that defers to after unlock), `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.

`DailyRun` steps: purge old data → plan & enqueue reminders (unless quiet day) → kick `OutboxWorker` → weekly backup if due (Sunday, or first run after a missed Sunday) → refresh widget → post morning summary → set `lastDailyRunDay`. Each step wrapped so one failure can't stop the others; errors go to ErrorLog.

`OutboxWorker` (unique work, APPEND_OR_REPLACE): sends what pacing allows, then schedules itself for the next allowed time. No foreground service needed (short bursts).

Memory: `DailyRun` must not load all history into memory — query only what's needed (active members + recent messages). Target < 30 MB heap.

**Visible proof:** Home shows "Reminders last ran: today 9:00 AM" (amber if > 26 h, red if > 50 h with a "Fix" button).

---

## 10. Screens and design

### 10.1 Design system (match the prototype)
| Token | Value |
|---|---|
| Font | **Plus Jakarta Sans** (OFL), bundled in `res/font`: 400, 500, 600, 700, 800. One family; hierarchy from weight/size. Devanagari falls back to the system font automatically. |
| Type scale (sp) | Display 26/800 · Title 22/700 · Body-L 17/600 · Body 16/400 · Label 15/600 · Caption 13/400 · Section label 12/700, letter-spacing 0.1em, CAPS |
| Background | `#FAF7F2` (warm off-white) · dark mode `#141210` |
| Surface / card | `#FFFFFF`, 18 dp corners, 1 dp border `#EEE9E3`, shadow 0/1/3 at 5% · dark `#1F1C19` |
| Header | `#1C1917` with `#FAFAF9` text, greeting in `#FB923C` |
| Brand accent | `#E8590C` (logo, highlights) · **Buttons `#C2410C`** with white text (contrast ≥ 4.5:1) |
| Text | Primary `#1C1917` · secondary `#57534E` (≥ 4.5:1 on background) |
| Status chips | Overdue `#FEE4E2`/`#B42318` · Due soon `#FEF0C7`/`#93370D` · Paid `#DCFCE7`/`#166534` · Frozen/Left `#E7E5E4`/`#44403C` |
| Avatar | Initials on `#FFEDD5`, text `#9A3412` |
| Spacing | 4-dp grid; screen padding 16; card padding 12–16; list gap 8 |
| Touch targets | ≥ 48 dp (buttons 52–56 dp tall for primary actions) |
| Icons | Material Symbols **Outlined** as bundled vector drawables (Apache-2.0); no emoji in the UI |
| Motion | 150–250 ms fades/slides; respect "Remove animations" |

All colours live in `BrandColors` / `StatusColors` objects. Every screen must work with system font size at 130 % and in dark mode, with no clipped text. Support `TalkBack`: content descriptions on icon buttons, money read as "rupees".

### 10.2 Screens
1. **Splash** — dark; "JAI SHREE RAM" (accent, letter-spaced) at top; gym logo (or placeholder) centre; "MB Fitness Club" + "Jeetu Bhaiya"; "App by Swastik" at bottom. System SplashScreen API, then ≤ 1 s branded screen on cold start only.
2. **Lock** (if PIN on) — PIN pad + fingerprint/face button; "Forgot PIN" → recovery code.
3. **Home / Members** — header: logo (56 dp), "JAI SHREE RAM", "Jeetu Bhaiya", "MB Fitness Club · Wed, 4 Nov"; three stat tiles (Collected this month · Pending · Active). "Reminders last ran" line. Search. Tabs with counts (Overdue first). Member rows: avatar, name, plan · phone, status chip, "₹X due" badge. FAB "+ Add member". TEST MODE ribbon when on. Banners (one at a time, by priority): SMS permission lost → reminders not running → backup overdue → 60-day suggestion.
4. **Member detail** — profile card (plan, valid till, balance), primary **Record payment**, secondary Call · Send SMS · Freeze, payment history (tap → edit/delete), messages sent, consent + reminders toggles, Aadhaar row (masked, Show, Remove; §7.10), ⋮ menu: Edit, Mark as Left / Rejoin, Delete permanently.
5. **Record payment** — Renewal | Pay balance segmented control, plan cards, big amount field (numeric keypad), UPI/Cash/Other, summary card (new expiry, rule note, remaining), **Save** → snackbar with Receipt no., Show QR, Undo.
6. **UPI QR** — full screen, white, large QR, amount, payee name, UPI ID, "Scan with any UPI app". Screen stays awake while open. Built from §2's `upi://pay` format with proper URL encoding; note `tn` = "MB Fitness - Rahul".
7. **Add / Edit member** — Pick from contacts, name, phone, plan cards, join date, paid today (full/part), DOB (optional), "Under 18" toggle → guardian name/phone, Aadhaar number (optional) → its own consent checkbox (§7.10), SMS consent checkbox, notes.
8. **Today's closing** — date switcher; totals Cash / UPI / Other / Total; list of today's payments with receipt numbers; "Share summary" copies plain text to the clipboard.
9. **Broadcast** — audience chips, message, counter, preview bubble, pacing plan, Send.
10. **Reports** — month switcher, stat cards, 6-month bars (Canvas), active-members line, balance list.
11. **Messages** — tabs: Queued · Sent · Failed; broadcast groups collapsible; Retry/Cancel.
12. **Settings** — sections: Gym (logo, greeting, owner name, gym name, trainer phone, UPI ID & payee name, plans & prices) · Reminders (templates, quiet days, reminder time, daily limit, SIM, renewal rule, morning summary, test mode) · Data (import, backup now, restore, CSV export, backup folder, backup password, recycle bin) · Security (PIN, fingerprint, recovery code) · Help · Health check · About.
13. **Templates** — pack switcher (English/Hinglish, with "Reset"), template chips, on/off, "Sends: 3 days before expiry", editor, insert-placeholder chips, counter, live preview, Send test to me, Save.
14. **Quiet days** — explainer card with next quiet day, weekday toggles, holiday list (✕ to remove), add holiday (date + name).
15. **Plans** — list with price/months, add, edit, retire (retired plans stay on old payments).
16. **Recycle bin** — deleted members with days left, Restore.
17. **Health check** — see §13.
18. **Help** — 6 cards, each 3 steps with a small illustration.
19. **About** — logo, app name, version, "Designed & developed by Swastik", `DEV_CONTACT`.
20. **Onboarding** — see §11.

Empty states have a friendly line and one action ("No overdue members. Nice!"). Errors are plain language ("Number must be 10 digits"). Destructive actions are red and always confirm.

---

## 11. Onboarding and permissions

### 11.1 First-run flow (one step per screen, progress dots, each skippable but nagged later)
1. Welcome (logo, greeting).
2. Gym details: owner name, gym name, trainer phone, UPI ID + payee name, plans (pre-filled 1 month ₹1,200 · 3 months ₹3,000).
3. **SMS permission** → request `SEND_SMS`. A restricted permission is denied instantly with no system dialog; detect this (result = denied, no rationale, returned in < ~300 ms, app installed outside a store) → show a 3-picture guide: App info → ⋮ → **Allow restricted settings** → Permissions → SMS → Allow, with an "Open App info" button.
4. **SIM** (if 2 active SIMs) → pick; needs `READ_PHONE_STATE` only to list SIMs.
5. **Prepaid plan check:** "Does your SIM plan include free SMS (usually 100/day)?" Yes → cap 90. Not sure → cap 50 and a tip.
6. **Notifications** → `POST_NOTIFICATIONS` (Android 13+).
7. **Alarms & reminders** → `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (Android 12+); optional.
8. **Battery** → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; then **brand-specific card** (detect `Build.MANUFACTURER`): Xiaomi/Redmi/POCO: Autostart ON, Battery saver "No restrictions", lock app in recents; Samsung: "Never sleeping apps"; Realme/Oppo/OnePlus: "Allow background activity" + Auto-launch; Vivo: "Background power consumption" allowed. Deep-link where an intent exists, else show steps.
9. **Unused-app hibernation** → `IntentCompat.createManageUnusedAppRestrictionsIntent` → turn off "Pause app activity if unused".
10. **Backup folder** → pick a folder (suggest a Google Drive–synced or "MB Fitness Backups" folder) via SAF, persist permission.
11. **App lock** → set PIN (optional), show the **recovery code** once ("write this down").
12. **Logo** → pick from gallery (optional).
13. **Test** → turn on Test mode, send a test SMS to his own number, confirm it arrived → Test mode off.
14. Done → offer "Import members".

### 11.2 Manifest permissions (complete list)
Declared by us: `SEND_SMS` · `READ_PHONE_STATE` (SIM list only) · `POST_NOTIFICATIONS` · `SCHEDULE_EXACT_ALARM` · `RECEIVE_BOOT_COMPLETED` · `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` · `USE_BIOMETRIC` · `READ_CONTACTS` (only if multi-pick import needs it). Declare `<uses-feature android:name="android.hardware.telephony" android:required="false"/>`.

Merged from AndroidX libraries and **allowed** (normal permissions, no data leaves the phone): WorkManager's `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` and similar. Don't strip them (WorkManager relies on them). **`INTERNET` must never appear in the merged manifest** — Phase 8 checks this.

Expedited work on Android 8–11 runs as a short foreground service, so `DailyRunWorker` and `OutboxWorker` implement `getForegroundInfo()` with a quiet "Sending reminders…" notification.

---

## 12. Backup, restore, privacy (DPDP-ready)

- **Weekly auto backup** (Sunday's daily run, or the next run after a missed Sunday) to the chosen SAF folder: `mbfitness_backup_YYYY-MM-DD.json`, keep latest 8. Includes all tables, settings (minus PIN hash), and the logo as Base64. Includes `schemaVersion` and `appVersion`.
- **Manual:** Backup now · Restore (pick file → preview "120 members, 840 payments, last payment 3 Nov" → confirm → replaces data, after first saving an automatic safety backup of current data) · CSV export (members with balance; payments).
- **Optional backup password:** AES-256-GCM, key from PBKDF2 (≥ 200k iterations, random salt) using built-in `javax.crypto`. Off by default (a forgotten password = unreadable backups). If on, the password hint is shown on the Backup screen.
- **Android auto-backup** enabled via `dataExtractionRules` (includes DB + DataStore, excludes caches).
- **Aadhaar in backups:** a password-protected backup holds the full number inside the encrypted file, and restore re-encrypts it for this phone. A backup with no password holds only `aadhaarLast4` plus the ciphertext, which only this phone's hardware key can open: a same-phone restore keeps full numbers, while a new phone or a reinstall gets the last 4 digits only (uninstalling deletes the key). The restore preview says how many numbers will need re-entering. Android auto-backup only ever carries the ciphertext. CSV export never includes Aadhaar.
- **Restore safety:** validate JSON fully before touching the DB; restore in one transaction; restoring a newer `schemaVersion` into an older app is refused with a clear message.
- **Startup integrity check** (`PRAGMA quick_check`); on failure offer "Restore latest backup".
- **Privacy:** data never leaves the phone except backups he saves. PIN-protected app. Health notes are optional and kept minimal. "Delete permanently" erases all of a member's data after the recycle-bin period. Minors flagged with guardian details. Consent date stored. About screen lists the gym's contact for data questions (`trainerPhone`).
- Retention housekeeping: messages older than 18 months pruned; members LEFT for 3+ years show a "remove old records?" suggestion (never automatic).

---

## 13. Health check (Settings → Health check)

Each row: ✓ / ⚠ / ✗ + one-tap fix.
- Reminders last ran / next scheduled run
- SMS permission (and "restricted settings" hint if blocked)
- Exact alarms allowed
- Battery optimisation off · OEM autostart (where detectable)
- Notifications allowed
- App hibernation exempt
- SIM available (chosen SIM present)
- SMS sent today / daily limit · queued now
- Last backup + folder still writable
- Database OK · size
- Last abnormal exit reason (`ApplicationExitInfo`, e.g. `MemoryLimiter:AnonSwap`)
- Test mode off
- Aadhaar: how many saved, whether a PIN protects them, whether backups include them
- Error log (last 200, copy as text)

---

## 14. Quality bars

- Cold start < 1.5 s on a mid-range phone; member list with 300 members scrolls at 60 fps (LazyColumn, stable keys).
- APK < 15 MB. No ANRs: all DB/IO on `Dispatchers.IO`.
- Every money operation inside a Room transaction.
- No crash on: no SIM, airplane mode, SMS permission revoked mid-run, folder deleted, storage full, dark mode, 130 % font, landscape/foldable (layouts reflow; don't lock orientation — Android 17 ignores locks on large screens).
- Lint clean (no warnings ignored without a comment). Kotlin warnings as errors in `domain/`.

---

## 15. Testing plan

**Unit tests (`domain/`, JVM, fast — must be extensive):**
- Dates: leap years, month ends, year boundary.
- Renewal rules A/B, restart-after-60-days, partial/over-payment, balance, opening balance.
- `recalculateMember`: add/edit/delete/undo/freeze sequences; property test vs fresh replay.
- ReminderPlanner: every template's day, repeats & max, one-per-day priority, dedupe across reruns, consent off, reminders off, frozen/left, quiet weekday, quiet date, consecutive quiet days, catch-up after 3 missed days (truthful wording), birthday on 29 Feb.
- Pacing: 25-per-30-min window, daily cap, carry-over, priority order.
- SmsSegments: GSM-7 vs Unicode, ₹, Devanagari, emoji, 160/161/306/307 boundaries.
- TemplateEngine: all placeholders, `[[…]]` balance clause, plural days, unknown placeholder error, link detection.
- PhoneNumbers, CsvParser (quotes, commas, BOM, CRLF, bad rows, `aadhaar` column, Excel scientific notation), UpiLink encoding, receipt numbering.
- Aadhaar: Verhoeff valid and invalid numbers, every single-digit typo and adjacent swap is caught, first digit 0 or 1 rejected, wrong length, spaces and dashes, masking, `toString()` never reveals the number. Test numbers are made up, never real people's.

**Integration tests:** Room migrations (from schema 1), backup → wipe → restore round-trip (byte-identical re-export), Aadhaar across both backup types and a missing key, outbox with `FakeSmsSender` (success, generic failure → retry, no service → wait).

**UI tests (Compose):** add member → record partial payment → balance badge → pay balance → badge gone; quiet day blocks run; broadcast pacing message.

**Device test matrix (manual, Phase 8):** bhaiya's actual phone + one Xiaomi/Redmi + one Samsung (or emulators API 26, 31, 34, 36, 37). Reboot test, airplane-mode test, 24-hour idle test, dual-SIM test.

---

## 16. Release, install and handover

**Signing & keys**
- Generate `mbfitness-release.jks` once. Store keystore + passwords in **two places** (your Google Drive + a pen drive) and a third copy of the passwords on paper. Never commit it; `keystore.properties` is git-ignored.
- `versionCode` increments every build given to bhaiya; `versionName` 1.0.0, 1.0.1…

**Developer verification (before 2027)**
- Create a free **limited distribution** account in the Android Developer Console and register `com.swastik.mbfitness` with the release key, so installs/updates on certified phones keep working once global enforcement starts in 2027. ADB/USB installs remain exempt regardless.

**Installing on bhaiya's phone (do it in person)**
1. On his phone: Settings → About → tap Build number 7× → Developer options → **USB debugging** on.
2. Connect to your laptop → `adb install -r app-release.apk` (or Android Studio "Run" with the release variant). USB/ADB is exempt from developer verification and avoids the "downloaded APK" restrictions.
3. If SMS permission is still blocked: App info → ⋮ → Allow restricted settings → Permissions → SMS → Allow.
4. Turn USB debugging off afterwards.
5. Updates later: same key, same package, `adb install -r` — data is kept.

**Go-live checklist**
1. Run onboarding fully; Test mode on; test SMS reaches his phone.
2. Add yourself + one friend; trigger each template using backdated expiries; "Run reminders now"; run again → nothing new sent.
3. Partial payment → balance reminder path; edit + delete + undo a payment.
4. Mark today quiet → nothing sent; remove → sends.
5. Broadcast to the two of you; watch pacing.
6. Import the sample CSV with a deliberately bad row.
7. Reboot the phone; airplane mode for an hour; leave overnight → 9 AM run + morning summary.
8. Backup → uninstall → reinstall → restore → spot-check balances.
9. Test mode **off**. Import the real 50 members. Ask him to tell members about SMS reminders (consent).
10. **First week:** check Health check every 2 days (or ask him to send a screenshot).

**Hand over:** a one-page printed guide (from the Help screen content) + your number.

---

## 17. Build phases

Each brief assumes you've read `docs/RULES.md` and `docs/SPEC.md`. After every phase: run tests, lint and a debug build; commit; update `docs/PROGRESS.md`.

**Phase 0 — Project setup**
> Create the Android project per SPEC §5 (package `com.swastik.mbfitness`, min 26, target/compile 37 or 36, Compose, Room+KSP, WorkManager, DataStore, Glance, biometric, splashscreen, kotlinx.serialization, ZXing core) with pinned versions in libs.versions.toml, R8 for release, no INTERNET permission. Add the package skeleton from §5.3, `docs/DECISIONS.md`, `docs/PROGRESS.md`, and a `scripts/check.sh` that runs `testDebugUnitTest lintDebug assembleDebug`.
*Done when:* empty app builds; check.sh passes; merged manifest has no INTERNET (add a Gradle check task that fails the build if it ever appears).

**Phase 1 — Domain core (pure Kotlin)**
> Implement everything in `domain/` per SPEC §7 and §8 (Dates, MemberRules incl. recalculateMember, ReminderPlanner incl. catch-up, QuietDays, TemplateEngine, SmsSegments, Pacing, PhoneNumbers, CsvParser, UpiLink, ReceiptNumbers) with the full unit-test list in §15. No Android imports.
*Done when:* all §15 unit tests exist and pass; coverage of `domain/` ≥ 90 %.

**Phase 2 — Data layer**
> Room entities/DAOs/AppDatabase per §6 (exportSchema, indexes), DataStore settings, Repository with transactional payment save/edit/delete/undo, recycle bin, MemberEvents, receipt numbers, seeding (plans, both template packs, Sunday quiet day). Migration test scaffold.
*Done when:* repository integration tests pass (payment round-trips, undo, delete/restore).

**Phase 3 — Design system & app shell**
> Theme, bundled Plus Jakarta Sans, colours, type, components (cards, chips, pills, stat tiles, primary/secondary buttons, banners, snackbar), navigation, splash, lock screen, Home header with logo + greeting per §10. Match the prototype's look.
*Done when:* shell runs in light/dark, 130 % font, TalkBack labels present.

**Phase 4 — Members & payments UI**
> Screens 3–8, 15, 16 of §10.2: Home (tabs, search, banners, multi-select), Member detail, Record payment (with summary, snackbar Undo, receipt), UPI QR, Add/Edit (contacts picker, consent, minor/guardian), Today's closing, Plans, Recycle bin.
*Done when:* the §15 UI test for payments passes; all flows work on 300 seeded members.

**Phase 5 — SMS engine & scheduling**
> Outbox, SimSmsSender/TestModeSender/FakeSmsSender, sent/delivered receivers, retries, pacing, DailyRun, alarms + watchdog + app-open check, boot/time receivers, morning summary + Problems channel, Messages screen, Broadcast, Templates editor (packs, counter, link blocking), Quiet days screen, "Run reminders now" preview. Test mode forced on in debug.
*Done when:* outbox integration tests pass; a debug build sends paced test SMS to the trainer number; rerunning the day sends nothing new.

**Phase 6 — Onboarding, settings, safety**
> Onboarding §11 (incl. restricted-settings guide and OEM battery cards), Settings §10.2-12, Health check §13, backup/restore/CSV/password §12, integrity check, error log, PIN + biometric + recovery code, Help and About screens.
*Done when:* backup → wipe → restore round-trip test passes; Health check shows correct states when permissions are toggled.

**Phase 7 — Reports, birthdays, widget**
> Reports §4-E with Canvas charts and month switcher (using MemberEvent), BIRTHDAY in planner + summary, Glance widgets (small/medium) refreshed after DailyRun and payments.
*Done when:* numbers match a hand-calculated seeded dataset; widget updates after a payment.

**Phase 8 — Hardening & release**
> Run the §14 checks and the §15 device matrix list as a checklist, fix issues, verify the manifest/dependency bans from §5.2, R8 release build, signing with `mbfitness-release.jks` via `keystore.properties`, produce `app-release.apk`, write `docs/HANDOVER.md` (install steps §16 + one-page guide for bhaiya), and remind me to back up the keystore and register the package for developer verification.
*Done when:* signed APK installs over USB on a real phone and the go-live checklist passes.

---

## 18. Deliberately not in 1.0 (possible later)

WhatsApp sending (needs paid API or risky automation) · attendance / QR check-in · diet & workout plans · body progress · trainer/staff logins · multi-branch · cloud sync · online payments / auto-debit · GST invoices · lead/enquiry CRM · Hindi UI · scheduled broadcasts · referral tracking. The data model leaves room for these without breaking backups.

---

## 19. References (checked Nov 2026)

- Android developer verification — https://developer.android.com/developer-verification and FAQ https://developer.android.com/developer-verification/guides/faq
- Sideloading timeline — https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/
- Android 15 restricted settings (SMS permission) — https://androidauthority.com/android-15-restricted-settings-sideloading-3481098 ; user reports on Android 16 — https://community.appinventor.mit.edu/t/sendmessagedirect-messagereceived-setting-sms-permissions-on-android-16/173745
- Play Protect India pilot — https://techcrunch.com/2024/10/03/google-pilots-blocking-some-sideloaded-apps-in-india
- Android outgoing SMS limit (30 / 30 min) — https://www.xda-developers.com/change-sms-limit-android/
- Exact alarms on Android 14 — https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Android 17 release notes — https://developer.android.com/blog/posts/android-17-is-here
- OEM background killing — https://dontkillmyapp.com/xiaomi
- TRAI spam / UCC — https://www.trai.gov.in/what-spam-or-ucc
- DPDP Rules timeline — https://www.tcsa.in/resources/dpdp-rules-2025-implementation-roadmap
- Aadhaar (Sharing of Information) Regulations 2016 — https://indiacode.ecourtsindia.com/rules/the-aadhaar-sharing-of-information-regulations-2016-8aadd223/ ; UIDAI's Dec 2025 announcement on copies — https://www.biometricupdate.com/202512/india-to-ban-aadhaar-photocopying-as-uidai-moves-to-regulate-private-sector-verification
- UPI deep links — https://dev.to/srvmhptr/how-upi-deep-links-work-upipay-build-a-payment-link-and-qr-code-without-a-payment-gateway-g8m
- `SmsManager.createForSubscriptionId` — https://learn.microsoft.com/en-us/dotnet/api/android.telephony.smsmanager.createforsubscriptionid

