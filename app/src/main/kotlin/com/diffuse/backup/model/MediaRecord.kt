package com.diffuse.backup.model

enum class MediaKind(val sourceId: String) {
    IMAGE("images"),
    VIDEO("video"),
}

/**
 * One MediaStore row (image or video). Photos and videos are **not** part of the
 * SMS Backup & Restore format (a JPEG/MP4 already *is* a standard format), so their
 * bytes are uploaded as original files and described by the `photos-*.xml` /
 * `videos-*.xml` index.
 *
 * GOTCHA: `datetaken` (note: no underscore) is epoch **milliseconds** and can be 0;
 * `date_added` / `date_modified` are epoch **seconds**. [dateTakenEpochMs] is null
 * when unset; the others are already converted to ms.
 */
data class MediaRecord(
    val id: Long,
    val kind: MediaKind,
    val displayName: String?,
    /** RELATIVE_PATH, e.g. "DCIM/Camera/", used to mirror the folder tree on Drive. */
    val relativePath: String?,
    val mimeType: String?,
    /**
     * MediaStore's `_size`. Informational only: it is unreliable on the Light Phone III
     * (fresh captures report 0), so uploads use the file descriptor's real length instead.
     */
    val sizeBytes: Long,
    /** epoch ms from `datetaken`; null when 0/unset. */
    val dateTakenEpochMs: Long?,
    /** epoch ms, converted from `date_added` seconds. */
    val dateAddedEpochMs: Long,
    /** epoch ms, converted from `date_modified` seconds. */
    val dateModifiedEpochMs: Long,
    /** `generation_modified`, MediaStore's per-volume change counter (recorded in the index). */
    val generationModified: Long,
    /** String form of the row's content URI, opened read-only to stream the bytes. */
    val contentUri: String,
) {
    /**
     * Identity of this item across runs, e.g. `images:123`. MediaStore ids are stable for
     * the life of an item, so this is what the backup uses to recognise "already uploaded",
     * rather than the file name (two different photos can share a name, and an edited photo
     * keeps its name while its bytes change).
     */
    val stableId: String get() = "${kind.sourceId}:$id"
}
