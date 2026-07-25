package com.diffuse.drive

/**
 * Resolves (find-or-create) and caches the Drive folder id for an archive-relative directory,
 * mirroring the archive's `media/<relative_path>/…` tree into Drive on demand. Shared by
 * [DriveUploader] (the XML docs at the archive root) and [DriveMediaSink] (streamed media),
 * so both agree on the same folders and never create duplicates.
 *
 * The empty relative dir `""` maps to [rootId] — the `Diffuse` folder. Every intermediate
 * folder is ensured once and memoised, so a run of thousands of `DCIM/Camera/…` items makes
 * one `ensureFolder` call per distinct directory, not per file.
 *
 * READ-ONLY: only touches Drive via [DriveApi]; no content-provider access.
 */
class DriveFolderTree(
    private val drive: DriveApi,
    rootId: String,
) {
    // archive-relative dir (forward-slashed, no leading/trailing slash) → Drive folderId.
    private val cache = HashMap<String, String>().apply { put("", rootId) }

    /** The Drive folder id for archive-relative dir [relDir] (creating ancestors as needed). */
    fun folderId(relDir: String): String {
        cache[relDir]?.let { return it }
        val parent = relDir.substringBeforeLast('/', missingDelimiterValue = "")
        val name = relDir.substringAfterLast('/')
        val id = drive.ensureFolder(name, folderId(parent))
        cache[relDir] = id
        return id
    }
}
