# Diffuse

**One-way, read-only backup for the Light Phone III.** Diffuse is a sideloaded
Android app that incrementally backs up your **SMS/MMS, call log, photos, and
videos** to *your own* Google Drive — and never writes a single byte back to the
phone.

It was built for the LP3 community by an LP3 owner who wanted a real backup
without handing their messages to a third-party service or risking their daily
driver. Your data goes to a `Diffuse` folder in your own Drive and nowhere else.

> **Status:** working and verified end-to-end on a physical Light Phone III
> (LightOS, Android 14 / SDK 34). Version `0.1.0`. MIT licensed.

---

## Why this exists

The LP3 has no built-in backup for messages, call history, or camera media. Most
"backup apps" want write access to your phone and send data through their own
servers. Diffuse takes the opposite approach:

- **Read-only, structurally.** The app declares only `READ_*` permissions. It
  never requests the default-SMS role, so the OS itself would throw on any write
  attempt. There is no code path that inserts, updates, or deletes anything on
  the phone — enforced by a CI guard (`scripts/check-readonly.sh`) that fails the
  build if a forbidden permission or mutating call ever sneaks in.
- **Your Drive, least privilege.** Uploads use the `drive.file` scope only,
  which means Diffuse can see *only the files it creates* — never the rest of
  your Drive.
- **Incremental.** After the first run it uploads just what's new, and skips
  anything already on Drive.
- **Restore-friendly format.** Messages and calls are written as
  [SMS Backup & Restore](https://synctech.com.au/sms-backup-restore/)–compatible
  XML, so you can restore them onto a normal Android phone with an existing tool.

---

## What it backs up

| Content        | Source                                   | On Drive                        |
| -------------- | ---------------------------------------- | ------------------------------- |
| SMS / MMS      | Standard telephony provider              | `sms-<timestamp>.xml`           |
| Call log       | Call log provider                        | `calls-<timestamp>.xml`         |
| Photos         | MediaStore (images)                      | `photos-<timestamp>.xml` + files |
| Videos         | MediaStore (video)                       | `videos-<timestamp>.xml` + files |

Media files are **streamed** directly from the phone to Drive (no local staging
copy). Runs happen in a fixed, predictable order: **call log → SMS/MMS → photos
→ videos.**

You can turn any of these categories off in Settings; re-enabling later loses
nothing (Diffuse tracks each category's progress independently).

---

## How it works

1. **Connect Drive (once).** Tap *Connect Drive* and a QR code appears. Scan it
   with any other device, sign in to Google, and tap *Allow*. Diffuse uses the
   OAuth 2.0 Device Authorization flow (RFC 8628) — the same "sign in on a TV"
   flow — and stores an encrypted refresh token, so you only do this once.
2. **Back up.** Tap *Back up now*, or let the scheduled job run. Diffuse reads
   the on-device data, writes the XML index docs, and streams everything to a
   `Diffuse` folder in your Drive with live progress.
3. **Stays in sync.** Before each run Diffuse reconciles against Drive: if you
   deleted backups on Drive, it notices and re-uploads them rather than assuming
   they're still there.

**Scheduling** is on by default: **daily, on Wi-Fi, while charging.** You can
change the frequency (off / daily / weekly), the time of day, and the Wi-Fi and
charging requirements in Settings.

---

## Install

Diffuse is a sideloaded app — it isn't on any app store. You'll need the
Light Phone III's **Developer Mode** enabled to install it over USB.

### Option A — install a prebuilt release APK

Download the latest `diffuse-<version>.apk` from the
[Releases page](../../releases), then with the phone connected in Developer Mode:

```bash
adb install -r diffuse-<version>.apk
```

Release APKs are signed and have the Drive credentials already built in, so you
can install and connect straight away — no build step, no Google Cloud setup on
your side. (Maintainers: how releases are produced is documented in
[docs/releasing.md](docs/releasing.md).)

### Option B — build from source

**Prerequisites**

- **JDK 21** (a full JDK, not just a JRE — the Android build needs `jlink`).
- **Android SDK** with `platform-tools`, `platforms;android-34`, and
  `build-tools;34.0.0`. Point `ANDROID_HOME` at it (or set `sdk.dir` in
  `local.properties`).
- The Gradle **wrapper is included** (`./gradlew`), so you don't need Gradle
  installed system-wide.

**1. Set up your Google Drive OAuth client (one time).**
See [docs/drive-setup.md](docs/drive-setup.md) for the click-by-click steps. In
short: create a Google Cloud project, enable the Drive API, configure the OAuth
consent screen (External, add your Google account as a Test user), and create an
OAuth client of type **TVs and Limited Input devices**. This client type is
required — it's the only one that supports the QR/device flow and the
`drive.file` scope.

Then drop the credentials into `local.properties` (git-ignored):

```properties
DRIVE_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
DRIVE_CLIENT_SECRET=xxxxxxxxxxxxxxxxxx
```

Without credentials the project still builds and tests pass, but the *Connect
Drive* button will just report that credentials are missing.

**2. Build and install to a connected phone:**

```bash
export ANDROID_HOME="$HOME/Android/Sdk"     # or wherever your SDK lives
./gradlew :app:installDebug
```

Or produce an APK you can hand to someone:

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

**3. On the phone:** open Diffuse → *Connect Drive* → scan the QR → *Allow* →
*Back up now*. Grant the read permissions when prompted, and a `Diffuse` folder
appears in your Drive.

---

## Permissions

Diffuse requests exactly these, and nothing that can modify your phone:

| Permission | Why |
| --- | --- |
| `READ_SMS` | read messages to back up |
| `READ_CALL_LOG` | read call history to back up |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | read photos/videos to back up |
| `ACCESS_MEDIA_LOCATION` | preserve photo location metadata |
| `INTERNET` / `ACCESS_NETWORK_STATE` | upload to Drive, check connectivity |
| `POST_NOTIFICATIONS` | show backup progress/completion |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | run scheduled backups reliably |

There is **no** `WRITE_SMS`, `WRITE_CALL_LOG`, `MANAGE_*`, or storage-write
permission, and the app never becomes the default SMS handler.

### Verifying it's really read-only

You don't have to take my word for it:

- **Inspect the manifest** — `app/src/main/AndroidManifest.xml` lists only the
  permissions above.
- **Run the guard** — `scripts/check-readonly.sh` greps the manifest and source
  for any mutating provider call or forbidden permission and fails if it finds
  one. It runs in CI on every change.
- **Snapshot your data** — `scripts/readonly-guard.sh` hashes each provider's
  contents (counts + SHA-256, no PII) before and after a run so you can confirm
  nothing changed.

---

## About restore

Diffuse is a **backup** tool — it intentionally has zero write surface on the
phone. Messages and calls are stored in SMS Backup & Restore's XML format, so you
can restore *those* today with that app on any Android phone. A guarded,
opt-in restore path (including media back into MediaStore) is designed but not
built; see [docs/restore-design.md](docs/restore-design.md) for the safety model.

---

## Documentation

- [docs/drive-setup.md](docs/drive-setup.md) — set up the Google Drive OAuth client.
- [docs/releasing.md](docs/releasing.md) — maintainer guide to cutting a signed public release.
- [docs/privacy-policy.md](docs/privacy-policy.md) — what data Diffuse touches and where it goes.
- [docs/backup-format.md](docs/backup-format.md) — the exact XML archive schema.
- [docs/restore-design.md](docs/restore-design.md) — restore safety model & format completeness.
- [docs/phase0-findings.md](docs/phase0-findings.md) — which LP3 content providers actually return data.

---

## Tech notes

- **Kotlin + Jetpack Compose**, `minSdk 33` / `targetSdk 34`, single `com.diffuse`
  app module plus a small vendored `:lightui` module (Light Phone's design tokens).
- Scheduling via **WorkManager**; credentials stored with **EncryptedSharedPreferences**.
- The core logic is seam-tested with fakes (no Robolectric, no network) — the
  suite is ~60 fast unit tests: `./gradlew :app:test`.

---

## Disclaimer

Diffuse is an independent community project. It is **not** affiliated with,
endorsed by, or supported by The Light Phone / Light Inc. Use it at your own
risk. It is read-only by design and verified as such, but it comes with no
warranty — see [LICENSE](LICENSE).

## License

[MIT](LICENSE) © 2026 Andrew Blodgett.
```
