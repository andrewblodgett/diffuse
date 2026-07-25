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
 * Mirrors the local archive tree produced by [com.diffuse.backup.BackupRunner] into a
 * `Diffuse` folder on Drive: the timestamped `*.xml` documents at the top level and the
 * `media/<relative_path>/…` originals in a matching subfolder tree. Each file is uploaded
 * with [DriveClient.upload] (which routes small→simple, large→resumable), and anything
 * already recorded in the [UploadManifest] is skipped, so re-runs are cheap and never
 * duplicate.
 *
 * Pure logic over [DriveClient] + [UploadManifest]; unit-tested with a fake DriveClient.
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
        // Cache of archive-relative-dir → Drive folderId; "" is the root folder.
        val folderIds = HashMap<String, String>().apply { put("", rootId) }

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
                val parentId = folderId(relDir, folderIds)
                val id = drive.upload(file.name, parentId, mimeOf(file), file)
                manifest.record(rel, id)
                uploaded++
                bytes += file.length()
            }

        return UploadSummary(uploaded, skipped, bytes, rootId)
    }

    /** Resolve (creating and caching) the Drive folder id for archive-relative dir [relDir]. */
    private fun folderId(relDir: String, cache: MutableMap<String, String>): String {
        cache[relDir]?.let { return it }
        val parent = relDir.substringBeforeLast('/', missingDelimiterValue = "")
        val name = relDir.substringAfterLast('/')
        val parentId = folderId(parent, cache)
        val id = drive.ensureFolder(name, parentId)
        cache[relDir] = id
        return id
    }

    /** Archive-relative path with forward slashes (Drive/manifest are platform-neutral). */
    private fun File.relTo(base: File): String =
        relativeTo(base).path.replace(File.separatorChar, '/')

    private fun mimeOf(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}
