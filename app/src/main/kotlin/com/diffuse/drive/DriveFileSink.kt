package com.diffuse.drive

import com.diffuse.backup.FileSink
import com.diffuse.drive.store.UploadManifest
import java.io.File
import java.net.URLConnection

/**
 * Uploads each finished archive document (`calls.xml`, `sms.xml`, `photos.xml`, `videos.xml`)
 * to Drive the moment [BackupRunner][com.diffuse.backup.BackupRunner] hands it over — replacing
 * the old approach of writing every doc locally first and sweeping the whole directory
 * afterward, which uploaded them in alphabetical-filename order regardless of the order the
 * progress UI announced.
 *
 * The docs have stable names and each run rewrites the complete export, so unlike media (whose
 * immutable, content-addressed bytes are skipped once recorded), a doc already in [manifest] is
 * **overwritten in place** — same Drive file id — rather than skipped or re-created. That's what
 * keeps repeat runs from either piling up duplicate documents or stranding a stale one on Drive.
 * A [backupPath] not yet in the manifest is uploaded fresh and recorded; if [reconcile] pruned it
 * (the user deleted the doc on Drive), that's exactly what re-creates it.
 *
 * READ-ONLY: reads the already-written local [file] and uploads it; touches no content provider.
 */
class DriveFileSink(
    private val drive: DriveApi,
    private val folders: DriveFolderTree,
    private val manifest: UploadManifest,
) : FileSink {

    /** Documents newly created on Drive this run. */
    var uploaded: Int = 0
        private set
    /** Documents overwritten in place (manifest hit) this run. */
    var updated: Int = 0
        private set

    override fun put(file: File, backupPath: String) {
        val existingId = manifest.fileId(backupPath)
        if (existingId != null) {
            drive.update(existingId, mimeOf(file), file)
            updated++
            return
        }
        val relDir = backupPath.substringBeforeLast('/', missingDelimiterValue = "")
        val id = drive.upload(file.name, folders.folderId(relDir), mimeOf(file), file)
        manifest.record(backupPath, id)
        uploaded++
    }

    private fun mimeOf(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}
