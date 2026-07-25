# Restore design (Phase 4 — design only, no restore code ships)

Diffuse is a one-way backup today. This document is the **forward design** for eventually
letting users get their data *back* — decided in Phase 4 but deliberately **not implemented**
in this build. It exists so restore is a first-class, safety-gated feature when it lands, not a
bolt-on that quietly erodes the read-only guarantee.

The only code this design adds now is the empty seam
[`com.diffuse.restore.RestoreSink`](../app/src/main/kotlin/com/diffuse/restore/RestoreSink.kt) —
an interface with no implementation and no provider-mutating call, so
`scripts/check-readonly.sh` stays green and the shipping APK stays provably read-only.

## The tension

The whole safety story is that Diffuse **cannot** write on-device data:

1. **Manifest** declares only `READ_*` + network perms — no `WRITE_SMS`, no `MANAGE_*`, and it
   never requests the default-SMS role, so the OS throws `SecurityException` on any write.
2. **Types** — [`BackupSource`](../app/src/main/kotlin/com/diffuse/backup/BackupSource.kt)
   exposes only reads; there is no write surface to reach for.
3. **CI** — `scripts/check-readonly.sh` fails the build on any `insert/update/delete`,
   output stream, writable file mode, or SMS-role escalation in source.

Real restore requires exactly the opposite: writing SMS/MMS into telephony, calls into the
call log, and media back into MediaStore — and for messages, **becoming the default SMS app**
(only the default SMS app may write to the SMS/MMS providers on modern Android). That is a
fundamental capability inversion, so it must be quarantined, not sprinkled in.

## Chosen model: one app, two modes, hard boundary

Per the Phase 4 decision, restore will eventually live **inside Diffuse** behind an explicit
opt-in — not a second app — but gated so the backup experience keeps its airtight guarantee:

- **Backup mode (today):** read-only, exactly as shipped. Default. Never asks for write perms.
- **Restore mode (future):** entered only by a deliberate, clearly-labelled user action. It is
  the *only* place write permissions / the SMS role are ever requested, and only at the moment
  the user commits to a restore.

### Non-negotiable safeguards for any future `RestoreSink` implementation

1. **Explicit, user-initiated only.** Never automatic, never scheduled, never triggered by a
   backup run. A backup and a restore can never happen in the same operation.
2. **Dry-run preview + confirmation.** Before writing anything, show exactly what would be
   written (counts per source, target, and the fact that restoring messages requires making
   Diffuse the default SMS app temporarily). Require an explicit confirm.
3. **Request escalated perms lazily and narrowly.** `ROLE_SMS` / write perms are requested at
   the point of restore, scoped to restore mode, and the app should offer to hand the default-SMS
   role back afterwards.
4. **Recommended packaging — build-flavor split.** The cleanest structural guarantee is two
   product flavors: a `backup` flavor whose manifest has only `READ_*` (what the CI guard checks
   and what gets sideloaded to a daily driver — carries *no* write surface at all) and a `full`
   flavor that adds the write perms + restore UI. Phase 4 documents this but does not build it;
   the current single manifest stays read-only. When restore is implemented, `check-readonly.sh`
   should target the `backup` flavor's manifest/source set so its guarantee is unchanged.
5. **Idempotent + non-destructive.** Restore should skip items already present (dedupe by the
   record identity already carried in the archive) and never delete existing on-device data.

## What the archive already carries (completeness check)

Restore is only possible if the backup captured enough. It did — the format
([docs/backup-format.md](backup-format.md)) is restore-oriented by design:

| Source | Restore target | Archive has what's needed? |
| --- | --- | --- |
| SMS | telephony `content://sms` | **Yes.** SMS Backup & Restore schema; all columns round-trip (dates in provider-native units). Already restorable *today* by the SyncTech app on a target phone. |
| MMS | telephony `content://mms` | **Yes.** Parts + addresses + SMIL kept; binary bytes inline base64; `<mms date>` in seconds as the provider expects. |
| Call log | `content://call_log/calls` | **Yes.** Full `<call>` rows in the SyncTech schema. |
| Images/Video | MediaStore | **Metadata yes, mechanism separate.** The `photos-*.xml`/`videos-*.xml` indices keep `relativePath`, `displayName`, mime, sizes and dates — enough to recreate each item under its original relative path. But MediaStore restore is a distinct *write* path (insert a pending item → stream bytes from Drive → publish), not a SyncTech concern, so it's the main net-new work when restore is built. |

**Gap to close when implementing:** media restore needs a MediaStore writer (the mirror of
[`MediaSource`](../app/src/main/kotlin/com/diffuse/backup/provider/MediaSource.kt)) plus a Drive
*download* path (the mirror of [`DriveMediaSink`](../app/src/main/kotlin/com/diffuse/drive/DriveMediaSink.kt)).
Messages/calls need a telephony writer usable only while Diffuse holds the SMS role. All of it
sits behind `RestoreSink` and the safeguards above.

## Summary

Phase 4 ships **no** restore code — only the `RestoreSink` seam and this design. The archive is
already restore-complete for messages/calls (via the SyncTech app even now) and carries the
metadata media restore will need. When restore is built, it must follow the two-mode model with
the five safeguards, ideally as a separate build flavor so the backup artifact stays provably
read-only.
