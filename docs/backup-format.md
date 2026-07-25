# Diffuse backup format (Phase 2)

Diffuse extracts on-device data into an **SMS Backup & Restore-compatible** archive on
local storage. This is the "standard format" the whole pipeline targets; Phase 3 uploads
the archive directory to Google Drive unchanged. Choosing the SyncTech format means the
messages/calls can be restored onto a normal Android phone, not just archived.

## Layout

```
<outputDir>/
  sms-<yyyyMMddHHmmss>.xml     # <smses> root: <sms> + <mms> (attachments inline base64)
  calls-<yyyyMMddHHmmss>.xml   # <calls> root: <call>
  media-<yyyyMMddHHmmss>.xml   # <medias> index (Diffuse's own; not SyncTech)
  media/<relative_path>/<name> # photo/video originals, copied byte-for-byte
```

Photos and videos are **not** part of the SMS Backup & Restore schema — a JPEG/MP4 is
already a standard format — so they are copied out as their original files and described
by the `media-*.xml` index rather than folded into XML.

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
  - `<part seq ct name chset cd fn cid cl ctt_s ctt_t text data>` — text/SMIL parts carry
    `text`; binary attachments carry base64 bytes in **`data`**. The SMIL layout part is
    **kept**, not discarded (restore needs it).
  - `<addr address type charset>` — `type`: 129=BCC, 130=CC, 137=From, 151=To.

## `calls-*.xml` (`<calls>`)

`<call number duration date type presentation subscription_id readable_date contact_name>`.
`date` is epoch **milliseconds**; `duration` is **seconds**. `type`: 1=incoming,
2=outgoing, 3=missed, 4=voicemail, 5=rejected, 6=blocked.

## `media-*.xml` (`<medias>`, Diffuse-specific)

`<media kind id display_name relative_path mime_type size date_taken date_added
date_modified generation_modified backup_path>`. All timestamps are epoch **ms** in the
index (normalised from the provider's mixed units). `backup_path` is where the bytes were
copied under `media/`.

## Notes / provenance

- `contact_name` is always `(Unknown)` — Diffuse does not request `READ_CONTACTS`.
- `readable_date` is cosmetic (`MMM d, yyyy h:mm:ss a`); restore ignores it.
- Absent values render as the literal string `null`, per the SyncTech format.
- Epoch-unit and column-name handling follows the Phase 0 gotchas in
  [phase0-findings.md](phase0-findings.md). Reference for the field set:
  <https://www.synctech.com.au/sms-backup-restore/fields-in-xml-backup-files/>.

## Incremental backups

Each source persists a change **token** (`FilePropertiesTokenStore`) in each provider's
**native column units** — SMS/call `date` (ms), MMS `date` (seconds), MediaStore
`generation_modified`. A run selects `column > token`; ms conversions happen only when
building records, never in the WHERE clause, so units can't drift. Tokens are captured
before extraction and persisted after a successful write, so the failure mode is a
re-backed-up boundary item (harmless), never a lost one.

## Read-only guarantee

Extraction opens provider cursors and, for MMS attachments and media, read-only input
streams — nothing else. The only writes are files under `<outputDir>` (our own sandbox),
which is not a provider mutation. `scripts/check-readonly.sh` enforces this statically.
