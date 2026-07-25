package com.diffuse.backup.provider

import android.database.Cursor

/**
 * [Row] backed by the current position of an Android [Cursor]. READ-ONLY: it only
 * ever reads column values. Column indices are resolved lazily and cached, and an
 * absent column (`getColumnIndex` == -1) or a NULL cell yields null.
 */
class CursorRow(private val cursor: Cursor) : Row {
    private val indexCache = HashMap<String, Int>()

    private fun index(column: String): Int =
        indexCache.getOrPut(column) { cursor.getColumnIndex(column) }

    override fun getString(column: String): String? {
        val i = index(column)
        return if (i < 0 || cursor.isNull(i)) null else cursor.getString(i)
    }

    override fun getLong(column: String): Long? {
        val i = index(column)
        return if (i < 0 || cursor.isNull(i)) null else cursor.getLong(i)
    }

    override fun getInt(column: String): Int? {
        val i = index(column)
        return if (i < 0 || cursor.isNull(i)) null else cursor.getInt(i)
    }
}
