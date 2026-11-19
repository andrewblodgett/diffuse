package com.diffuse.backup

import java.text.NumberFormat
import java.util.Locale

/**
 * The one-line, human-readable description of a finished run that the home screen and
 * notifications show, e.g. "412 calls, 10,077 messages, 351 photos (3 new); 2 items failed,
 * will retry". Pure, so the wording is unit-tested.
 */
object RunSummaryText {

    /**
     * @param included the categories this run actually backed up.
     * @param skippedForPermission enabled categories that were left out because their
     *   permission isn't granted.
     */
    fun describe(
        summary: BackupSummary,
        included: BackupContent,
        skippedForPermission: BackupContent = BackupContent.NONE,
    ): String {
        val parts = mutableListOf<String>()
        if (included.messages) {
            parts += "${n(summary.calls)} calls"
            parts += "${n(summary.sms + summary.mms)} messages"
        }
        if (included.pictures) parts += media(summary.photos, "photos")
        if (included.videos) parts += media(summary.videos, "videos")

        val notes = mutableListOf<String>()
        if (summary.failedItems > 0) {
            val noun = if (summary.failedItems == 1) "item" else "items"
            notes += "${n(summary.failedItems)} $noun failed, will retry"
        }
        val skipped = buildList {
            if (skippedForPermission.messages) add("messages & calls")
            if (skippedForPermission.pictures) add("photos")
            if (skippedForPermission.videos) add("videos")
        }
        if (skipped.isNotEmpty()) notes += "skipped ${skipped.joinToString(", ")}: permission needed"

        val main = parts.joinToString(", ").ifEmpty { "Nothing backed up" }
        return if (notes.isEmpty()) main else main + "; " + notes.joinToString("; ")
    }

    private fun media(t: MediaTally, noun: String): String {
        val backedUp = t.uploaded + t.alreadyBackedUp
        return if (t.uploaded > 0) "${n(backedUp)} $noun (${n(t.uploaded)} new)" else "${n(backedUp)} $noun"
    }

    private fun n(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
}
