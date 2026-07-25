package com.diffuse.drive

import com.diffuse.backup.MediaSink
import com.diffuse.backup.model.MediaRecord
import com.diffuse.drive.store.UploadManifest
import java.io.InputStream

/**
 * A [MediaSink] that streams each photo/video **straight from the content provider to Drive**,
 * with no local copy — the fix for Phase 3's ~3.3 GB on-device duplication. The provider stream
 * is opened read-only and piped into a resumable/simple upload; peak memory is one copy buffer.
 *
 * Idempotent like the rest of the pipeline: anything already recorded in [manifest] (keyed by
 * the archive-relative `backupPath`, exactly as [DriveFileSink] keys XML docs) is skipped,
 * so re-runs never re-upload and existing manifests keep working after the switch to streaming.
 *
 * The Android coupling is two seams: [openStream] turns a record's `contentUri` into a
 * read-only [InputStream], and [lengthOf] returns the item's real byte length from its file
 * descriptor. Both keep this class pure and unit-testable with fakes + a fake [DriveApi].
 *
 * **Why [lengthOf] exists:** MediaStore's `_size` column is unreliable on the Light Phone III —
 * fresh camera captures report `_size=0` for a file that is really several MB. A streamed upload
 * must declare an accurate Content-Length up front (and it drives simple-vs-resumable routing),
 * so when the record's size is missing we probe the descriptor's real `statSize` instead. Trusting
 * `_size` here caused a 6 MB photo to upload as "0 bytes", be rejected, and get silently dropped.
 *
 * READ-ONLY: [openStream]/[lengthOf] open the provider in "r" mode and the bytes are uploaded;
 * nothing here writes back to any content provider.
 */
class DriveMediaSink(
    private val openStream: (contentUri: String) -> InputStream,
    private val lengthOf: (contentUri: String) -> Long,
    private val drive: DriveApi,
    private val folders: DriveFolderTree,
    private val manifest: UploadManifest,
) : MediaSink {

    /** Media streamed to Drive this run. */
    var uploaded: Int = 0
        private set
    /** Media already present (manifest hit) and skipped this run. */
    var skipped: Int = 0
        private set
    /** Bytes streamed to Drive this run. */
    var bytesUploaded: Long = 0L
        private set

    override fun put(record: MediaRecord, backupPath: String) {
        if (manifest.contains(backupPath)) {
            skipped++
            return
        }
        val relDir = backupPath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = backupPath.substringAfterLast('/')
        val parentId = folders.folderId(relDir)
        val mime = record.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        // Trust the record's size only if MediaStore actually gave us one; otherwise probe the
        // descriptor's real length (see class docs — LP3 camera captures report _size=0).
        val length = record.sizeBytes.takeIf { it > 0 } ?: lengthOf(record.contentUri)
        require(length > 0) { "unknown length for ${record.contentUri}" }
        val id = drive.upload(name, parentId, mime, length) { openStream(record.contentUri) }
        manifest.record(backupPath, id)
        uploaded++
        bytesUploaded += length
    }
}
