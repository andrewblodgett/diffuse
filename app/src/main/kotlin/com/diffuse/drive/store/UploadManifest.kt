package com.diffuse.drive.store

import java.io.File
import java.util.Properties

/**
 * Records which archive files have already been uploaded to Drive (archive-relative
 * path → Drive fileId), so re-running a backup skips work already done and never
 * duplicates. Mirrors the `Properties`-file approach of
 * [com.diffuse.backup.store.FilePropertiesTokenStore].
 *
 * If the manifest is ever lost, the worst case is re-uploading (folder creation stays
 * idempotent via [com.diffuse.drive.DriveClient.ensureFolder]); it is an optimization,
 * not a correctness dependency. Writing our own app file is not a provider mutation.
 */
class UploadManifest(private val file: File) {

    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    /** True if [relativePath] was already uploaded. */
    fun contains(relativePath: String): Boolean = props.containsKey(relativePath)

    /** The Drive fileId previously recorded for [relativePath], or null. */
    fun fileId(relativePath: String): String? = props.getProperty(relativePath)

    /** Record a successful upload and persist immediately. */
    fun record(relativePath: String, fileId: String) {
        props.setProperty(relativePath, fileId)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Diffuse Drive upload manifest") }
    }
}
