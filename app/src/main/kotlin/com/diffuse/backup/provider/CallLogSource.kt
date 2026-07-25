package com.diffuse.backup.provider

import android.content.ContentResolver
import android.provider.CallLog
import com.diffuse.backup.model.BackupItem

/**
 * Extracts the call log from `content://call_log/calls`. Token = `date` (epoch **ms**).
 * READ-ONLY: the app declares only READ_CALL_LOG.
 */
class CallLogSource(resolver: ContentResolver) : ContentProviderSource(
    resolver = resolver,
    uri = CallLog.Calls.CONTENT_URI,
    projection = PROJECTION,
    tokenColumn = "date",
) {
    override val id: String get() = "call_log"

    override fun map(row: Row): BackupItem? = CallMapper.map(row)

    private companion object {
        val PROJECTION = arrayOf(
            "_id", "number", "duration", "date", "type", "presentation", "subscription_id",
        )
    }
}
