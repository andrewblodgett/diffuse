package com.diffuse.backup.provider

/** In-memory [Row] for tests. Missing keys and explicit nulls both read back as null. */
class FakeRow(private val values: Map<String, Any?>) : Row {
    override fun getString(column: String): String? = values[column]?.toString()

    override fun getLong(column: String): Long? = when (val v = values[column]) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is String -> v.toLongOrNull()
        else -> v.toString().toLongOrNull()
    }

    override fun getInt(column: String): Int? = getLong(column)?.toInt()
}
