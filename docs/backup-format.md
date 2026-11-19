# Diffuse backup format

Diffuse exports on-device data into an **SMS Backup & Restore-compatible** archive and
uploads it to a `Diffuse` folder in the user's Google Drive. Choosing the SyncTech format
means the messages/calls can be restored onto a normal Android phone, not just archived.

## Layout

```
Diffuse/
  calls-2026-11.xml     # <calls> root: <call>
  sms-2026-11.xml       # <smses> root: <sms> + <mms> (attachments inline base64)
  photos-2026-11.xml    # <medias> index for images (Diffuse's own; not SyncTech)
  videos-2026-11.xml    # <medias> index for video, same shape
  calls-2026-10.xml     # last month's snapshots, frozen
  ...
  media/DCIM/Camera/IMG_0001.jpg    # originals, mirroring each item's RELATIVE_PATH
```

**Monthly snapshots.** Every run exports each enabled source in full and writes it to the
*current month's* document (named from the phone's local date when the run starts). A later
run in the same month overwrites that document in place (same Drive file id); once the month
is over its documents are never modified again. Deleting something on the phone therefore
only removes it from the current month's snapshot. A source with no rows produces no
document, so an empty export can never replace a useful one.

Each document uploads the moment it's written, in this fixed order (call log, messages,
photos, videos), so the order things appear in Drive matches what the backup UI announces.

Photos and videos are **not** part of the SMS Backup & Restore schema (a JPEG/MP4 is
already a standard format), so their bytes stream straight to Drive (see `DriveMediaSink`)
and are described by the `photos-*.xml`/`videos-*.xml` index rather than folded into XML.

Every document's root `count` attribute is the number of records actually written, counted
while writing (not queried beforehand, since the provider can change mid-run).

## `sms-*.xml` (`<smses>`)

`<smses count backup_set backup_date type="full">`. Holds both message kinds.

- `<sms>` — `protocol address date type subject body toa sc_toa service_center read
  status locked date_sent sub_id readable_date contact_name`. `date`/`date_sent` are
  epoch **milliseconds** (provider-native). `type`: 1=received, 2=sent, 3=draft, 4=outbox,
  5=failed, 6=queued.
- `<mms>` — `date ct_t msg_box rr sub read_status address m_id read m_size m_type sub_id
  readable_date contact_name`, containing `<parts>` and `<addrs>`.
  - **`date` is epoch _seconds_** (provider-native) — the writer converts the record's ms
    back down. This asymmetry with `<sms>` is required: restore writes each attribute
    straight into its provider column, and the MMS column is seconds.
  - `msg_box`: 1=received, 2=sent, 3=draft, 4=outbox. `m_type`: 128=send-req (sent),
    132=retrieve-conf (received), …
  - `<part seq ct name chset cd fn cid cl ctt_s ctt_t text data>`. A part whose payload the
    provider keeps in its `text` column (plain text, HTML, the SMIL layout) carries `text`;
    every other part carries its file's bytes, base64-encoded, in **`data`**. That includes
    textual attachments such as vCards (`text/x-vCard`), which AOSP stores as files. The SMIL
    layout part is **kept**, not discarded (restore needs it).
  - `<addr address type charset>` — `type`: 129=BCC, 130=CC, 137=From, 151=To.

## `calls-*.xml` (`<calls>`)

`<call number duration date type presentation subscription_id readable_date contact_name>`.
`date` is epoch **milliseconds**; `duration` is **seconds**. `type`: 1=incoming,
2=outgoing, 3=missed, 4=voicemail, 5=rejected, 6=blocked.

## `photos-*.xml` / `videos-*.xml` (`<medias>`, Diffuse-specific)

`<media kind id display_name relative_path mime_type size date_taken date_added
date_modified generation_modified backup_path drive_file_id>`. All timestamps are epoch
**ms** in the index (normalised from the provider's mixed units). `backup_path` is where the
bytes are in the archive under `media/` (including any ` (edited …)`/` (id)` suffix), and
`drive_file_id` is the exact Drive file. Items that failed to upload during a run are left
out of that run's index and retried by the next run. Split into two documents (one all `kind="images"`, one all
`kind="video"`) so each streams and reports progress independently.

## Notes / provenance

- `contact_name` is always `(Unknown)` — Diffuse does not request `READ_CONTACTS`.
- `readable_date` is cosmetic (`MMM d, yyyy h:mm:ss a`); restore ignores it.
- Absent values render as the literal string `null`, per the SyncTech format.
- Epoch-unit and column-name handling follows the Phase 0 gotchas in
  [phase0-findings.md](phase0-findings.md). Reference for the field set:
  <https://www.synctech.com.au/sms-backup-restore/fields-in-xml-backup-files/>.

## How Diffuse knows what's already on Drive

Drive is the source of truth; nothing is tracked in a local database. Every file Diffuse
uploads carries two private Drive `appProperties`:

| Property | Media file | Document |
| --- | --- | --- |
| `diffuseKey` | `media:<kind>:<MediaStore _id>`, e.g. `media:images:123` | `doc:<file name>`, e.g. `doc:sms-2026-11.xml` |
| `diffuseVersion` | the file's byte length | SHA-256 of the document's record section |
| `diffuseOrigin` | short hash of the item's `date_added` and name | (none) |
| `diffuseFolder` | archive folder it was uploaded to (omitted if too long for a property) | (none) |

Each run lists the app's files on Drive (under `drive.file` that is exactly the files
Diffuse created) and groups them by `diffuseKey`. A media file only counts as an item's
backup if its `diffuseOrigin` matches too: MediaStore ids restart on a new phone or a rebuilt
media database, so `images:42` alone could name an unrelated photo. Among matching files,
the newest wins. Then for each item:

- **Media**, same key and version → already backed up; the bytes aren't even read.
  Same key, different length (edited, or first caught while still being written) → the new
  version is uploaded *next to* the old one as `name (edited YYYY-MM-DD).ext`; nothing is
  ever overwritten. A different item whose name is already taken gets its MediaStore id
  appended: `name (123).ext`.
- **Documents**, same fingerprint → skipped (no daily re-upload of an unchanged archive).
  Different fingerprint → the file's content is replaced, keeping its id. The fingerprint
  covers only the records, not the header, whose `backup_date`/`backup_set` change on every
  run.

Consequences: files deleted from Drive are uploaded again on the next run, and a reinstall
on the same phone recognises everything already on Drive. A new phone's media is uploaded
as new files (with an id suffix where a name is already taken). A short local journal of the last
day's uploads covers the moments when a brand-new file doesn't show in Drive's listing yet.

Files uploaded by Diffuse 0.1.x have no tags; on the first run after upgrading they are
matched through 0.1.x's local `upload-manifest.properties` and tagged in place, then that
file is deleted. Each 0.1.x file is claimed by at most one item, and only if its size on
Drive equals the item's current length; otherwise the current bytes are uploaded as an
edited version.

Lengths come from the file descriptor, never MediaStore's `_size`, which reads 0 for fresh
captures on the Light Phone III.

## Read-only guarantee

Extraction opens provider cursors and, for MMS attachments and media, read-only input
streams — nothing else. The only local writes are the documents being assembled in the
app's private `files/backup/` directory (deleted after upload), which is not a provider
mutation. `scripts/check-readonly.sh` enforces this statically.
