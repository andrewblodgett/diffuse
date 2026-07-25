# Drive setup — one-time OAuth client for Diffuse (Phase 3)

Diffuse uploads its backup archive to **your** Google Drive using the OAuth 2.0
Device Authorization flow (RFC 8628) — the "TV / limited-input device" flow — so
sign-in on the Light Phone III is just **scan a QR code on another device and tap
Allow**. That flow needs one thing set up once: an OAuth **client_id** and
**client_secret** that identify the *app* to Google. These are the same for every
install and are **not** per-user; for a sideloaded personal app they aren't true
secrets, but we still keep them out of git.

You only do this once. After that, connecting Drive on the phone is a QR scan.

## 1. Create a Google Cloud project

1. Go to <https://console.cloud.google.com/> and create a project (any name).
2. **APIs & Services → Library →** search **Google Drive API → Enable**.

## 2. Configure the OAuth consent screen

1. **APIs & Services → OAuth consent screen.**
2. User type: **External**. Fill in the required app name / support email.
3. **Scopes:** you don't have to add any here; Diffuse requests only
   `https://www.googleapis.com/auth/drive.file` at sign-in (least privilege —
   the app can only see files it creates, never your other Drive files).
4. **Test users:** add the Google account you'll back up to. While the app is in
   "Testing" this is required; you don't need to publish/verify it for personal
   use.

## 3. Create the OAuth client

1. **APIs & Services → Credentials → Create credentials → OAuth client ID.**
2. Application type: **TVs and Limited Input devices**.
   *(This is required — the QR/device flow only works with this client type, and
   it only supports the `drive.file` / `drive.appdata` scopes, not full `drive`.)*
3. Copy the generated **Client ID** and **Client secret**.

## 4. Drop the credentials into the build

Add these two lines to `local.properties` at the repo root (this file is
git-ignored, so the credentials never get committed):

```properties
DRIVE_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
DRIVE_CLIENT_SECRET=xxxxxxxxxxxxxxxxxx
```

They're read at build time into `BuildConfig.DRIVE_CLIENT_ID` /
`BuildConfig.DRIVE_CLIENT_SECRET` (see `app/build.gradle.kts`). With no
credentials the project still builds and all unit tests pass — the Connect Drive
button just reports that credentials are missing.

## 5. Use it on the phone

1. Build & install: `./gradlew :app:installDebug` (with `ANDROID_HOME` exported).
2. Tap **Connect Drive** → a QR code appears.
3. Scan it with any other device, sign in, tap **Allow**. The phone finishes
   automatically and stores an encrypted refresh token — you won't sign in again.
4. Tap **Back up now** → grant the read permissions → a **Diffuse** folder appears
   in your Drive with the archive (`calls-*.xml`, `sms-*.xml`, `photos-*.xml`,
   `videos-*.xml`, and a `media/` subtree), uploaded in that order. Re-running skips
   files already uploaded.

## Notes

- **Scope:** `drive.file` only. Diffuse never requests access to your existing
  Drive files.
- **Read-only on the phone:** the upload path only *reads* on-device data and
  writes to Drive / app storage; it never modifies SMS, call log, or media. See
  `scripts/check-readonly.sh`.
- The device code expires after ~15 minutes; if you don't finish in time, tap
  Connect again for a fresh QR.
