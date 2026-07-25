package com.diffuse.drive

import com.diffuse.drive.store.UploadManifest
import java.io.File
import java.net.URLConnection

/** What an upload run did. */
data class UploadSummary(
    val uploaded: Int,
    val skipped: Int,
    val bytesUploaded: Long,
    val rootFolderId: String,
)

/**
 * Uploads the local archive files produced by [com.diffuse.backup.BackupRunner] into a
 * `Diffuse` folder on Drive, mirroring their directory structure. As of Phase 4 media
 * originals stream straight to Drive during extraction ([DriveMediaSink]) and never land on
 * disk, so in practice this uploads just the small timestamped `*.xml` documents — but it
 * still walks the whole tree, so any local file (e.g. a leftover from an older run) is handled.
 *
 * Each file is uploaded with [DriveClient.upload] (small→simple, large→resumable); anything
 * already in the [UploadManifest] is skipped, so re-runs are cheap and never duplicate.
 * Folder resolution is shared with [DriveMediaSink] via [DriveFolderTree].
 *
 * Pure logic over [DriveApi] + [UploadManifest]; unit-tested with a fake DriveApi.
 * READ-ONLY: reads local archive files and uploads them; touches no content provider.
 */
class DriveUploader(
    private val drive: DriveApi,
    private val manifest: UploadManifest,
    private val rootFolderName: String = "Diffuse",
) {
    /** Upload every file under [outputDir], mirroring its directory structure to Drive. */
    fun upload(outputDir: File): UploadSummary {
        val rootId = drive.ensureFolder(rootFolderName)
        val folders = DriveFolderTree(drive, rootId)

        var uploaded = 0
        var skipped = 0
        var bytes = 0L

        outputDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relTo(outputDir) }
            .forEach { file ->
                val rel = file.relTo(outputDir)
                if (manifest.contains(rel)) {
                    skipped++
                    return@forEach
                }
                val relDir = rel.substringBeforeLast('/', missingDelimiterValue = "")
                val id = drive.upload(file.name, folders.folderId(relDir), mimeOf(file), file)
                manifest.record(rel, id)
                uploaded++
                bytes += file.length()
            }

        return UploadSummary(uploaded, skipped, bytes, rootId)
    }

    /** Archive-relative path with forward slashes (Drive/manifest are platform-neutral). */
    private fun File.relTo(base: File): String =
        relativeTo(base).path.replace(File.separatorChar, '/')

    private fun mimeOf(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}
