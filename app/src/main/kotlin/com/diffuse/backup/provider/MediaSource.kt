package com.diffuse.backup.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.diffuse.backup.model.MediaKind
import com.diffuse.backup.model.MediaRecord

/**
 * Extracts MediaStore images or video, in `_id` order (stable, so progress and the index
 * read the same way every run). MediaStore's default query already hides pending
 * (still being written) and trashed items. READ-ONLY: reads rows only; the bytes are
 * opened read-only later by the media sink.
 *
 * Photos/videos are backed up as their original files (a JPEG/MP4 already *is* a
 * standard format), not folded into the SMS Backup & Restore XML.
 */
class MediaSource(
    resolver: ContentResolver,
    private val kind: MediaKind,
    baseUri: Uri,
) : ContentProviderSource<MediaRecord>(
    resolver = resolver,
    uri = baseUri,
    projection = PROJECTION,
    sortColumn = "_id",
) {
    override fun map(row: Row): MediaRecord? {
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
