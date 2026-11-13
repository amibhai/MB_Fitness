# MB Fitness Club

A small Android app that keeps the fee records for one gym and texts members when their fees are due.

It was made for MB Fitness Club and runs on the owner's phone. It's specific to that gym: the home screen greets the owner by name, it assumes rupees, UPI and Indian phone numbers, and it leaves out things a bigger setup would want, like staff logins, multiple branches and online payments. It isn't on the Play Store and is installed directly from an APK.

## What it does

- Members, plans and payments, with receipts and an edit history for every payment. A payment taken when someone joins is dated their join date.
- Automatic SMS reminders from the phone's own SIM, paced slowly and sent only to members who agreed to them, starting with a welcome SMS when they're added
- A UPI QR for the exact amount, a daily cash and UPI total, and simple monthly reports
- Automatic backups, and a way to send a copy off the phone
- An optional Aadhaar number for each member, with their consent, stored encrypted and shown masked

The app has no internet permission, so it can't upload anything by itself. There's no server, account or subscription, and the only running cost is the SMS plan the phone already has.

## Building it

You'll need Android SDK 37 and a JDK (it's built with JDK 21).

```
cd mbfitness
./gradlew assembleDebug
```

A debug build installs beside the real app as "MB Fitness (test)" and only ever texts the phone number saved in its settings. `scripts/check.sh` runs the tests, lint and the debug build. Release builds are signed with a key that isn't in this repo.

## Versions

The current release is 1.0.2. Each release installs over the one before and keeps all the data, because it's signed with the same key.

- **1.0.2**: a payment taken on Add member is dated the join date, not the day it was typed in, with its own payment date for when the two differ. A new member who agrees to SMS gets a welcome message. The member page shows the join date, and changing it moves their first payment with it. Payments entered on 1.0.1 are moved to the join date on the first open, wherever nothing else changes.
- **1.0.1**: it recovers by itself from crashes, damaged files and a wrong clock, keeps a daily copy of the data, and can send a backup off the phone.
- **1.0.0**: the first release.

## Notes

It's kept up for this one gym, so it only changes when that gym needs it to. You're welcome to borrow from it under the [MIT licence](LICENSE).

More detail is in [mbfitness/docs](mbfitness/docs): the [spec](mbfitness/docs/SPEC.md), the [rules](mbfitness/docs/RULES.md) the code sticks to, and the [decisions](mbfitness/docs/DECISIONS.md) behind it.
