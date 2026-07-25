package com.diffuse.backup.format

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Formats the human-readable `readable_date` attribute the way SMS Backup & Restore
 * does (e.g. "Jan 1, 2021 12:00:00 AM"). Cosmetic — restore ignores it — but included
 * for fidelity. Uses the device default time zone.
 */
internal object ReadableDate {
    private val format = ThreadLocal.withInitial {
        SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US)
    }

    fun format(epochMs: Long): String = format.get()!!.format(java.util.Date(epochMs))
}

/** Placeholder used when a contact name can't be resolved (READ_CONTACTS is out of scope). */
internal const val UNKNOWN_CONTACT = "(Unknown)"
