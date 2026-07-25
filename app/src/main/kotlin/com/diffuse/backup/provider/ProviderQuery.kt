package com.diffuse.backup.provider

import android.content.ContentResolver
import android.net.Uri

/**
 * READ-ONLY query helpers shared by the extractors. Every path here opens a cursor
 * for reading and closes it; nothing mutates the provider. The static read-only
 * guard (scripts/check-readonly.sh) additionally forbids any insert/update/delete.
 */

/** Iterates a query's rows as [Row]s, always closing the cursor. */
internal inline fun ContentResolver.forEachRow(
    uri: Uri,
    projection: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?,
    block: (Row) -> Unit,
) {
    query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        val row = CursorRow(cursor)
        while (cursor.moveToNext()) block(row)
    }
}

/** Number of rows a query would return, without materialising them. */
internal fun ContentResolver.countRows(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<String>?,
): Int {
    query(uri, arrayOf("_id"), selection, selectionArgs, null)?.use { return it.count }
    return 0
}

/**
 * Largest value of [column] currently in the table, or 0 when empty. Reads the first
 * row of a descending sort (no LIMIT — LIMIT-in-sortOrder is rejected by MediaStore
 * on modern Android, and a windowed cursor only fills its first window anyway).
 */
internal fun ContentResolver.maxLong(uri: Uri, column: String): Long {
    query(uri, arrayOf(column), null, null, "$column DESC")?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
    }
    return 0L
}
