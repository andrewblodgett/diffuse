package com.diffuse.drive

import com.diffuse.backup.FileSink
import com.diffuse.drive.store.UploadManifest
import java.io.File
import java.net.URLConnection

/**
 * Uploads each finished archive document (`calls-*.xml`, `sms-*.xml`, `photos-*.xml`,
 * `videos-*.xml`) to Drive the moment [BackupRunner][com.diffuse.backup.BackupRunner] hands it
 * over — replacing the old approach of writing every doc locally first and sweeping the whole
 * directory afterward, which uploaded them in alphabetical-filename order regardless of the
 * order the progress UI announced.
 *
 * Idempotent like the rest of the pipeline: anything already recorded in [manifest] (keyed by
 * [backupPath]) is skipped.
 *
 * READ-ONLY: reads the already-written local [file] and uploads it; touches no content provider.
 */
class DriveFileSink(
    private val drive: DriveApi,
    private val folders: DriveFolderTree,
    private val manifest: UploadManifest,
) : FileSink {

    /** Documents uploaded this run. */
    var uploaded: Int = 0
        private set
    /** Documents already present (manifest hit) and skipped this run. */
    var skipped: Int = 0
        private set

    override fun put(file: File, backupPath: String) {
        if (manifest.contains(backupPath)) {
            skipped++
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
