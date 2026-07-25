package com.diffuse.backup.provider

import android.content.ContentResolver
import android.provider.Telephony
import com.diffuse.backup.model.BackupItem

/**
 * Extracts SMS from `content://sms`. Token = `date` (epoch **ms**, native units).
 * READ-ONLY: reads the telephony provider; the app declares only READ_SMS and never
 * takes the default-SMS role, so any write would throw SecurityException anyway.
 */
class SmsSource(resolver: ContentResolver) : ContentProviderSource(
    resolver = resolver,
    uri = Telephony.Sms.CONTENT_URI,
    projection = PROJECTION,
    tokenColumn = "date",
) {
    override val id: String get() = "sms"

    override fun map(row: Row): BackupItem? = SmsMapper.map(row)

    private companion object {
        val PROJECTION = arrayOf(
            "_id", "address", "date", "date_sent", "type", "body", "subject",
            "read", "status", "protocol", "service_center", "locked", "sub_id",
        )
    }
}
