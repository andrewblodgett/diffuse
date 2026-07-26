package com.diffuse.drive.store

import java.io.File
import java.util.Properties

/**
 * Records which archive files have already been uploaded to Drive (archive-relative
 * path → Drive fileId), so re-running a backup skips media bytes already uploaded and
 * overwrites the stable-named XML docs in place instead of duplicating them. A simple
 * `java.util.Properties` file.
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

    /**
     * Drop every entry whose recorded fileId is not in [liveFileIds] — i.e. anything the
     * manifest believes is on Drive but that has actually been deleted (or trashed) there.
     * Returns true if anything was pruned, so the caller knows this run's "already backed up"
     * picture was stale. Persists immediately when it changes anything.
     */
    fun retain(liveFileIds: Set<String>): Boolean {
        val stale = props.stringPropertyNames().filter { props.getProperty(it) !in liveFileIds }
        if (stale.isEmpty()) return false
        stale.forEach { props.remove(it) }
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Diffuse Drive upload manifest") }
        return true
    }
}
