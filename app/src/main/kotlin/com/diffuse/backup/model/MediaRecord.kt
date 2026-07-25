package com.diffuse.backup.model


enum class MediaKind(val sourceId: String) {
    IMAGE("images"),
    VIDEO("video"),
}

/**
 * One MediaStore row (image or video). Photos and videos are **not** part of the
 * SMS Backup & Restore format — a JPEG/MP4 already *is* a standard format — so
 * these are copied out byte-for-byte and indexed in `media-<ts>.xml`.
 *
 * GOTCHA: `datetaken` (note: no underscore) is epoch **milliseconds** and can be 0;
 * `date_added` / `date_modified` are epoch **seconds**. [dateTakenEpochMs] is null
 * when unset; the others are already converted to ms.
 */
data class MediaRecord(
    val id: Long,
    val kind: MediaKind,
    val displayName: String?,
    /** RELATIVE_PATH, e.g. "DCIM/Camera/", used to reconstruct the tree on copy. */
    val relativePath: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    /** epoch ms from `datetaken`; null when 0/unset. */
    val dateTakenEpochMs: Long?,
    /** epoch ms, converted from `date_added` seconds. */
    val dateAddedEpochMs: Long,
    /** epoch ms, converted from `date_modified` seconds. */
    val dateModifiedEpochMs: Long,
    /** `generation_modified`: the monotonic per-volume incremental change-token source. */
    val generationModified: Long,
    /** String form of the row's content URI, opened read-only to copy the bytes. */
    val contentUri: String,
) : BackupItem {
    override val sourceId: String get() = kind.sourceId
    override val stableId: String get() = "${kind.sourceId}:$id"
    override val updatedAtEpochMs: Long get() = dateTakenEpochMs ?: dateModifiedEpochMs
}
