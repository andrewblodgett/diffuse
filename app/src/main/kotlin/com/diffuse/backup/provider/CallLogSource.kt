package com.diffuse.backup.provider

import android.content.ContentResolver
import android.provider.CallLog
import com.diffuse.backup.model.CallRecord

/**
 * Extracts the call log from `content://call_log/calls`, oldest first.
 * READ-ONLY: the app declares only READ_CALL_LOG.
 */
class CallLogSource(resolver: ContentResolver) : ContentProviderSource<CallRecord>(
    resolver = resolver,
    uri = CallLog.Calls.CONTENT_URI,
    projection = PROJECTION,
    sortColumn = "date",
) {
    override fun map(row: Row): CallRecord? = CallMapper.map(row)

    private companion object {
        val PROJECTION = arrayOf(
            "_id", "number", "duration", "date", "type", "presentation", "subscription_id",
        )
    }
}
