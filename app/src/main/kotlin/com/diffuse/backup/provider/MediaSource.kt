package com.diffuse.backup.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.diffuse.backup.model.BackupItem
import com.diffuse.backup.model.MediaKind

/**
 * Extracts MediaStore images or video. Token = `generation_modified` — the monotonic
 * per-volume counter confirmed populated on-device (docs/phase0-findings.md), which is
 * why media incremental keys off it rather than a timestamp. READ-ONLY: reads rows and,
 * during the copy step, opens each item's content URI as an input stream only.
 *
 * Photos/videos are backed up as their original files (a JPEG/MP4 already *is* a
 * standard format), not folded into the SMS Backup & Restore XML.
 */
class MediaSource(
    resolver: ContentResolver,
    private val kind: MediaKind,
    baseUri: Uri,
) : ContentProviderSource(
    resolver = resolver,
    uri = baseUri,
    projection = PROJECTION,
    tokenColumn = "generation_modified",
) {
    override val id: String get() = kind.sourceId

    override fun map(row: Row): BackupItem? {
        val itemId = row.getLong("_id") ?: return null
        val contentUri = ContentUris.withAppendedId(uri, itemId).toString()
        return MediaMapper.map(row, kind, contentUri)
    }

    companion object {
        val PROJECTION = arrayOf(
            "_id", "_display_name", "relative_path", "mime_type", "_size",
            "datetaken", "date_added", "date_modified", "generation_modified",
        )

        fun images(resolver: ContentResolver) =
            MediaSource(resolver, MediaKind.IMAGE, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

        fun video(resolver: ContentResolver) =
            MediaSource(resolver, MediaKind.VIDEO, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }
}
