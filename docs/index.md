---
title: Diffuse Privacy Policy
---

# Privacy Policy for Diffuse

**Last updated: 2026-07-25**

Diffuse is an open-source, independent backup app for the Light Phone III. It
copies your messages, call history, photos, and videos to **your own Google
Drive**. This policy explains exactly what data Diffuse accesses, why, and where
it goes.

**Diffuse has no servers.** It sends nothing to the developer or to any third
party. Your data travels only from your phone to your own Google Drive account.

## What data Diffuse accesses

Diffuse reads the following from your device solely to back it up:

- **SMS and MMS messages** (`READ_SMS`)
- **Call log entries** (`READ_CALL_LOG`)
- **Photos and videos** (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`), including their
  embedded location metadata (`ACCESS_MEDIA_LOCATION`) so backed-up photos keep
  the information they were taken with.

Diffuse is **read-only** on your phone. It holds no permission to modify or
delete any of this data, and never becomes the default SMS handler. It cannot
change anything on your device.

## How the data is used

- The data you choose to back up is uploaded directly to **your** Google Drive,
  into a folder named `Diffuse`.
- Diffuse uses the Google **`drive.file`** OAuth scope, which grants access
  **only to files Diffuse itself creates**. Diffuse cannot see, read, or touch
  any other file in your Google Drive.
- Diffuse performs no analytics, tracking, advertising, or profiling. There is no
  telemetry of any kind.

## Data stored on your device

- An **encrypted** Google OAuth refresh token, so you don't have to sign in
  repeatedly. It is stored using Android's EncryptedSharedPreferences and never
  leaves your phone except to talk to Google's own authentication servers.
- The **email address** of the connected Google account, shown in Settings so you
  know which account you're backing up to.

Both are removed when you tap **Sign out** in the app.

## Data shared with third parties

None, other than the transfer to **your own** Google Drive, which you authorize.
Diffuse's use of Google APIs is governed by
[Google's API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including its Limited Use requirements. Diffuse does not sell, transfer, or use
your data for any purpose other than performing the backup you requested.

## Your control and data deletion

- You control the backed-up data in your own Google Drive and can delete it there
  at any time.
- You can disconnect Diffuse from your Google account at any time by tapping
  **Sign out** in the app, or by revoking access at
  <https://myaccount.google.com/permissions>.
- Uninstalling the app removes all of Diffuse's on-device data, including the
  stored token.

## Children's privacy

Diffuse is a general-purpose backup utility and is not directed at children.

## Changes to this policy

Any changes will be posted at this URL with an updated "Last updated" date.

## Contact

Diffuse is an independent community project and is not affiliated with, endorsed
by, or supported by The Light Phone / Light Inc.

You can reach out to open-hummus-change@duck.com with any questions.
