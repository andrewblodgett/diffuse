# Releasing Diffuse — public release checklist

This is the end-to-end guide for shipping a **public** release APK that anyone in
the community can install and connect to their own Google Drive. It covers the
Google Cloud console steps (only you can do these), generating a signing key,
setting up CI secrets, and cutting a release.

Do the one-time setup (sections 1–5) once. After that, each release is just
section 6.

---

## 0. The credential model (read this first)

Diffuse signs in with Google's **Device Authorization flow** ("TVs and Limited
Input devices"). That OAuth client is a **public client** — Google knows the
`client_secret` ships inside the distributed APK and cannot be kept confidential.
So the "secret" is really just an app identifier, not a true secret.

The only realistic abuse if someone extracts it: they could display a consent
screen labeled "Diffuse" and back files up to **their own** Drive, or consume
your Cloud project's API quota. They **cannot** reach any other user's data —
tokens are per-user and the `drive.file` scope only ever exposes files the app
created. If the credentials are ever abused, rotate the OAuth client and cut a
new release.

We keep the creds out of git regardless: local dev reads them from
`local.properties`, CI reads them from GitHub Actions secrets.

---

## 1. One-time: publish the OAuth consent screen to Production

Your OAuth app currently sits in **Testing** status, which caps you at 100
manually-added test users **and expires every user's refresh token after 7 days**
— unacceptable for a set-and-forget backup app. To distribute publicly you must
move it to Production.

1. Google Cloud console → **APIs & Services → OAuth consent screen**.
2. Fill in, as needed for verification:
   - **App name:** Diffuse
   - **User support email**
   - **App logo** (optional but recommended)
   - **Application home page:** your GitHub Pages / repo URL
   - **Privacy policy URL:** the Pages URL from section 4 below
   - **Authorized domains:** the domain of those URLs (e.g. `github.io`)
3. Confirm the only scope requested is
   `https://www.googleapis.com/auth/drive.file`.
4. Click **Publish app** → status becomes **In production**.

**Verification:** `drive.file` is **not** a restricted scope, so you avoid the
expensive annual CASA security assessment. Google may still require lightweight
**brand verification** (logo/domain/policy checks) before or shortly after
publishing. The console is the source of truth — follow whatever it prompts. If
it asks you to "prepare for verification," that's the brand check, not the
restricted-scope assessment.

> The `client_id` / `client_secret` themselves do **not** change when you publish
> — you keep the same ones from `docs/drive-setup.md`.

---

## 2. One-time: generate a release signing key

Every install of an Android app must be signed by the same key for updates to
install over each other. Generate one and **guard it** — if you lose it, you can
never ship an update that upgrades an existing install; users would have to
uninstall/reinstall (losing the Drive connection).

```bash
keytool -genkeypair -v \
  -keystore diffuse-release.jks \
  -alias diffuse \
  -keyalg RSA -keysize 2048 -validity 10000
```

It prompts for a **keystore password**, a **key password** (you can use the same
value), and a name/org (any values are fine). Keep the resulting
`diffuse-release.jks` **out of git** (`.gitignore` already excludes `*.jks`) and
back it up somewhere safe (a password manager or encrypted store).

---

## 3. One-time: add GitHub Actions secrets

Base64-encode the keystore so it can live as a secret:

```bash
base64 -w0 diffuse-release.jks    # macOS: base64 -i diffuse-release.jks
```

Then in the repo: **Settings → Secrets and variables → Actions → New repository
secret**, add:

| Secret | Value |
| --- | --- |
| `DRIVE_CLIENT_ID` | your OAuth client id (`…apps.googleusercontent.com`) |
| `DRIVE_CLIENT_SECRET` | your OAuth client secret |
| `KEYSTORE_BASE64` | the base64 blob from the command above |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `diffuse` (or whatever `-alias` you used) |
| `KEY_PASSWORD` | the key password |

The [`release` workflow](../.github/workflows/release.yml) reads exactly these.

---

## 4. One-time: publish the privacy policy on GitHub Pages

Google requires a reachable **Privacy policy URL** to publish the consent screen.
The policy already lives at [`docs/privacy-policy.md`](privacy-policy.md); enable
Pages to serve it:

1. Fill in the `<!-- FILL IN -->` blanks in `docs/privacy-policy.md` (date +
   contact email) and commit.
2. Repo → **Settings → Pages → Build and deployment**:
   - **Source:** Deploy from a branch
   - **Branch:** `master`, **Folder:** `/docs`
3. After it builds, the policy is at
   `https://<your-username>.github.io/<repo>/privacy-policy` — use that URL in the
   consent screen (section 1).

---

## 5. One-time: (optional) build a signed release locally

You don't need this for CI, but to sanity-check signing on your machine, create a
git-ignored `keystore.properties` at the repo root:

```properties
KEYSTORE_FILE=diffuse-release.jks
KEYSTORE_PASSWORD=…
KEY_ALIAS=diffuse
KEY_PASSWORD=…
```

Make sure `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` are in `local.properties`,
then:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk  (minified + signed)
```

(With no keystore configured, `assembleRelease` still runs but produces an
**unsigned** APK.)

---

## 6. Cut a release

Once the setup above is done, releasing is just tagging:

1. Bump the version if you like — CI derives `versionName` from the tag
   (`v0.1.0` → `0.1.0`) and `versionCode` from the run number automatically.
2. Tag and push:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
3. The [`release` workflow](../.github/workflows/release.yml) builds the minified,
   signed APK, names it `diffuse-<version>.apk`, and attaches it to a new GitHub
   Release with auto-generated notes.
4. Announce the release link to the community. Users just download the APK and
   `adb install -r diffuse-<version>.apk`.

---

## Rotating credentials (if ever abused)

1. Google Cloud console → **Credentials** → delete the old OAuth client, create a
   new "TVs and Limited Input devices" client.
2. Update the `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` repo secrets.
3. Cut a new release. Existing users re-connect (scan the QR) once against the new
   client; their backed-up data in Drive is unaffected.
