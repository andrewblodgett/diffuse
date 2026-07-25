package com.diffuse.backup.provider

/**
 * A minimal, column-name-addressed view of a single provider row.
 *
 * This exists so the mapping logic (the part most likely to be silently wrong —
 * epoch units, the `datetaken` spelling, m_type direction) is *pure Kotlin* and can
 * be unit-tested with a plain fake, without Android's `Cursor` or Robolectric.
 * Missing columns return null rather than throwing, so a projection that varies
 * across devices degrades gracefully.
 */
interface Row {
    fun getString(column: String): String?
    fun getLong(column: String): Long?
    fun getInt(column: String): Int?
}
