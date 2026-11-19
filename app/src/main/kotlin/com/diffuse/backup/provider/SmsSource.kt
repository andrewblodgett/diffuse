package com.diffuse.backup.provider

import android.content.ContentResolver
import android.provider.Telephony
import com.diffuse.backup.model.SmsRecord

/**
 * Extracts SMS from `content://sms`, oldest first.
 * READ-ONLY: reads the telephony provider; the app declares only READ_SMS and never
 * takes the default-SMS role, so any write would throw SecurityException anyway.
 */
class SmsSource(resolver: ContentResolver) : ContentProviderSource<SmsRecord>(
    resolver = resolver,
    uri = Telephony.Sms.CONTENT_URI,
    projection = PROJECTION,
    sortColumn = "date",
) {
    override fun map(row: Row): SmsRecord? = SmsMapper.map(row)

    private companion object {
        val PROJECTION = arrayOf(
            "_id", "address", "date", "date_sent", "type", "body", "subject",
            "read", "status", "protocol", "service_center", "locked", "sub_id",
        )
    }
}
