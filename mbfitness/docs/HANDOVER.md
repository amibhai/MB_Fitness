# Handover

Two parts: what Swastik does to release the app, and a one-page guide to print for Jeetu Bhaiya.

---

# Part 1: releasing the app (for Swastik)

## 1. The signing key

Created on 6 Nov 2026, in `mbfitness/`:

- `mbfitness-release.jks`: the key. Alias `mbfitness`, RSA 4096, valid until 24 March 2054.
- `keystore.properties`: where the build finds it. The password is in this file, and only there.

Both are git-ignored. Never commit them.

**Back them up now.** Copy both files to two places, such as your Google Drive and a pen drive, and write the password from `keystore.properties` on paper. Never make a new key: an app signed with a different key can't update this one, so it would have to be uninstalled and set up again from a backup.

The certificate's SHA-256 fingerprint, which developer verification asks for, is public:

```
37:A5:C4:8E:73:78:3D:FC:52:48:F9:75:CE:69:19:ED:5F:22:01:9F:FD:34:E5:7F:E9:75:9E:E1:6D:BE:71:2D
```

## 2. Build the release

```
./gradlew assembleRelease
```

The file is `app/build/outputs/apk/release/app-release.apk`, signed with the key above. On a computer without `keystore.properties`, the build makes an unsigned APK instead, which won't install.

Raise `versionCode` in `app/build.gradle.kts` for every build you give bhaiya. `versionName` goes 1.0.0, 1.0.1 and so on.

## 3. Before giving it to anyone

- [ ] Add your contact to `DEV_CONTACT` in `ui/screens/settings/HealthAndInfoScreens.kt`, so About shows it. It's blank for now, which simply doesn't show.
- [ ] Replace the placeholder launcher icon if you have a real logo. The logo inside the app is picked in Settings.
- [ ] Register `com.swastik.mbfitness` with the release key in a free **limited distribution** account in the Android Developer Console, before 2027. Installs on certified phones need this once enforcement starts. USB installs stay exempt.

## 4. Install on bhaiya's phone, in person

1. On his phone: Settings, About, tap Build number 7 times. Then Developer options, turn on **USB debugging**.
2. Connect to your laptop and run `adb install -r app/build/outputs/apk/release/app-release.apk`.
3. If the SMS permission is still blocked: App info, the three dots, **Allow restricted settings**, Permissions, SMS, Allow. Onboarding shows these same steps if it happens.
4. Turn USB debugging off afterwards.

Updates later: same key, same package, `adb install -r`. His data stays.

## 5. Go-live checklist

Do these with the release build, on his phone.

- [ ] Run onboarding fully. Turn Test mode on in Settings, and check a test SMS reaches his phone.
- [ ] Add yourself and one friend. Use past expiry dates to trigger each template. Tap Run reminders now, then again: nothing new goes.
- [ ] Part payment, then the balance reminder. Edit, delete and undo a payment.
- [ ] Mark today as a quiet day: nothing goes. Remove it: reminders go.
- [ ] Broadcast to the two of you, and watch the pacing.
- [ ] Import the sample CSV with one deliberately bad row.
- [ ] Reboot the phone. Airplane mode for an hour. Leave it overnight, and check the 9 AM run and the morning summary.
- [ ] Back up, uninstall, reinstall, restore. Spot-check balances. Expect Aadhaar numbers to need re-entering unless a backup password was set.
- [ ] Test mode **off**. Import the real members. Ask him to tell members about SMS reminders, because consent has to be real.
- [ ] First week: open Health check every 2 days, or ask him for a screenshot.

## 6. Phones to try it on

Bhaiya's phone, plus one Xiaomi or Redmi and one Samsung if you can get them. These makers stop background apps most aggressively. On each: the reboot test, the airplane-mode test, a 24-hour idle test, and a dual-SIM test if the phone has two SIMs.

## 7. Before relying on the Aadhaar feature

The Aadhaar design follows my reading of the Aadhaar (Sharing of Information) Regulations 2016 and UIDAI's guidance. **It isn't legal advice, and the rules are changing.** Please have someone qualified confirm it before go-live. Add these lines to the gym's joining form:

> I agree to receive fee reminders by SMS from MB Fitness Club.

> I agree to share my Aadhaar number with MB Fitness Club for gym records. I understand this is optional.

---

# Part 2: one-page guide for Jeetu Bhaiya

Print this page.

## Every day

**Someone pays:** tap their name, then **Record payment**. Check the amount, then **Save**. If it's by UPI, **Show QR** shows a code they can scan. Made a mistake? **Undo** appears right after saving.

**New member:** on Home, tap **Add member**. Ask them if they agree to SMS reminders, and only then tick the box.

**Closing up:** the **Today** tab shows cash, UPI and the total.

## Reminders go by themselves

Every morning at 9, the app sends fee reminders, and you get a short notification saying what happened. You don't need to do anything.

**Sundays and holidays:** no reminders. To add a holiday: Settings, **Quiet days**.

**Someone says "don't message me":** open them, tap **Stop SMS**. Done.

**To message everyone:** SMS tab, then **Broadcast**. It goes out slowly on purpose, so your number doesn't get blocked. Don't put links in messages.

## If something looks wrong

**Go to Settings, then Health check.** Anything not green has a button to fix it.

| You see | Do this |
|---|---|
| "Reminders haven't run" | Open Health check and tap **Fix** |
| "SMS permission is off" | Tap **Turn on** |
| An SMS failed | SMS tab, **Failed**, tap **Retry** |
| "Your backup is overdue" | Tap **Back up** |

**Your phone may stop the app to save battery.** Health check shows the steps for your phone.

## Keep your data safe

Backups run every Sunday. **Don't delete the backup folder.**

If you set a PIN, write down the recovery code and keep it safe. It's the only way back in if you forget your PIN. If you set a backup password and forget it, those backups can never be opened.

**Aadhaar numbers:** only save one if the member agrees. It's optional for them. Never send one by SMS or photo. Delete any CSV file after importing it.

## Need help?

Call Swastik: ______________________
