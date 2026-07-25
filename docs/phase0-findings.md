# Phase 0 — Provider Feasibility Findings

**Date:** 2026-07-25
**Device:** Light Phone III (`TLP301`), on-device serial `LP3LHMA521400380`
**OS:** Android 14, API/SDK 34
**Build fingerprint:** `Light/LightPhoneIII/LightPhoneIII:14/UKQ1.240321.001/00WW_1_440000:user/release-keys`
**Security patch:** 2025-03-01 · **Display id:** `00WW_1_440000` (`ro.lightos.version` is unset; LightOS versions via the build incremental)
**Host:** Pop!_OS 24.04, `adb` 1.0.41 (platform-tools 34.0.4)

## Verdict: GO on all sources, including messages and MMS

The single question this phase existed to answer — *does LightOS's Messages tool write to the standard AOSP telephony provider, or to a private database?* — is answered **yes, standard provider.** `content://sms` returns the full, current, two-way message history. **No scope change is required.** SMS, MMS (with attachments), call logs, photos, and videos are all extractable through standard AOSP content providers.

All probes were run against real device data via the `shell` UID (`adb shell content query`), which is read-only: it opens a cursor and prints rows. Nothing was written, deleted, or migrated. Message bodies, phone numbers, and file names are deliberately excluded from this document — only counts, schema, and non-identifying metadata are recorded.

## Probe results

| Probe | URI | Result | Rows | Notes |
|---|---|---|---|---|
| 1. SMS (gate) | `content://sms` | **OK** | 7,286 | 185 distinct `thread_id`; `type=1` (received) and `type=2` (sent) both present; `date` values are current |
| 2a. MMS | `content://mms` | **OK** | 2,791 | `m_type=132` (retrieve-conf / received) |
| 2b. MMS parts | `content://mms/part` | **OK** | 5,762 | `application/smil` layout parts + `text/plain` + binary attachments |
| 2c. MMS addr | `content://mms/{id}/addr` | **OK** | 14 (sample id) | `type=137` (from) / `type=151` (to) readable |
| 3. Call log | `content://call_log/calls` | **OK** | 356 | Real `date`, `duration`, `type` |
| 4a. Images | `content://media/external/images/media` | **OK** | 351 | Volume `external_primary`; generation columns populated |
| 4b. Video | `content://media/external/video/media` | **OK** | 9 | Same schema as images |

### MMS attachment content types (from `mms/part.ct`)

```
2788  application/smil     (layout, discard)
2642  text/plain           (message body -> extract into body text)
 260  image/jpeg  ┐
  37  image/png   │
  20  image/gif   ├─ binary attachments -> extract to files
   7  video/3gpp  │
   4  text/x-vCard│
   2  audio/mpeg  │
   1  audio/mp4   │
   1  audio/amr   ┘
```

Real, varied attachments exist, so MMS attachment extraction is worth building and has live test data.

## Incremental strategy (media) — confirmed viable

`MediaStore` rows expose `generation_added` and `generation_modified`, both populated and monotonically increasing (max `generation_modified` observed: 39,701). This is the per-row mechanism the incremental change-token strategy relies on. The volume-level `MediaStore.getGeneration(context, VOLUME_EXTERNAL_PRIMARY)` call is app-code and is deferred to Phase 2, but the underlying columns behave as required. All media is on a single volume, `external_primary`.

## Permission model — de-risked

The device ships the **stock AOSP `com.android.permissioncontroller`** (versionName "33 system image", minSdk 30, targetSdk 34) plus `com.android.packageinstaller` — not a bespoke LightOS replacement. The plan's concern that runtime permission dialogs might not render for a non-SDK app is therefore low risk: a plain app using `ActivityResultContracts.RequestMultiplePermissions` goes through the standard controller. All five required runtime permissions (`READ_SMS`, `READ_CALL_LOG`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `ACCESS_MEDIA_LOCATION`) are defined on the platform.

**Caveat:** probes read via the `shell` UID, which has broader privileges than an installed app. Provider *existence* and *data* are proven; app-level access still depends on the runtime grants above being requested and granted. Visual confirmation that the dialog actually renders on LightOS's UI layer should be captured with the first APK build (Phase 1/2) — it is expected to work but has not yet been seen on-screen.

## Gotchas captured for Phase 2 (these will silently corrupt data if ignored)

1. **Inconsistent time units across providers.** Do not assume milliseconds everywhere.
   - `sms.date` → epoch **milliseconds** (13 digits)
   - `mms.date` → epoch **seconds** (10 digits) — multiply by 1000
   - `call_log.date` → epoch **milliseconds**
   - `media.datetaken` → epoch **milliseconds**; `media.date_added` / `date_modified` → epoch **seconds**
2. **`DATE_TAKEN` column string is `datetaken`, not `date_taken`.** `MediaStore.*.DATE_TAKEN` resolves to `"datetaken"` (no underscore), unlike `date_added` / `date_modified`. Querying `date_taken` throws `IllegalArgumentException: Invalid column`.
3. **MMS is a three-table join.** Body text lives in `text/plain` parts, layout in `application/smil` parts (discard), binaries in the remaining parts; participants in `content://mms/{id}/addr` (`type=137` from, `151` to). Watch for null `_data` on some parts — budget time here as the plan warns.
4. **`m_type` filters MMS direction/kind** (132 = received retrieve-conf); confirm sent-MMS handling (`m_type=128`) when building the extractor.

## Host setup & device connection (for reproducing)

- `adb` installed on the host via `apt` (`android-tools-adb`); udev granted access without extra rules.
- The LP3 enumerates under two USB identities: `18d1:4ee7` (Google, ADB-only interface) and `0489:c030` (Foxconn composite, **MTP + ADB** — descriptor exposes an "ADB Interface", class ff / subclass 0x42 / protocol 1). The composite mode is sufficient for `adb`; no cable or charge-only issue.
- Initial state was `unauthorized`; resolved by unlocking the phone and accepting the "Allow USB debugging?" prompt ("Always allow from this computer").

### Probe commands (reproducible, read-only)

```bash
adb shell content query --uri content://sms                          --projection _id:thread_id:date:type
adb shell content query --uri content://mms                          --projection _id:thread_id:date:m_type
adb shell content query --uri content://mms/part                     --projection _id:mid:ct
adb shell content query --uri content://mms/{id}/addr                --projection _id:type
adb shell content query --uri content://call_log/calls               --projection _id:date:duration:type
adb shell content query --uri content://media/external/images/media  --projection _id:datetaken:date_added:date_modified:generation_added:generation_modified
adb shell content query --uri content://media/external/video/media   --projection _id:datetaken:date_added:date_modified:generation_added:generation_modified
```

## Recommendation

Proceed to Phase 1 with **full original scope** — messages (SMS **and** MMS with attachments), call logs, photos, and videos. The MMS-descope contingency (ship SMS-only in v1) is **not** needed on capability grounds; it remains available purely as a schedule lever if the three-table extraction proves slower to harden than budgeted.
