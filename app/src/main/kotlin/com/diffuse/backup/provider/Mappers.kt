package com.diffuse.backup.provider

import com.diffuse.backup.model.CallRecord
import com.diffuse.backup.model.MediaKind
import com.diffuse.backup.model.MediaRecord
import com.diffuse.backup.model.MmsAddr
import com.diffuse.backup.model.MmsPart
import com.diffuse.backup.model.SmsRecord

/**
 * Pure [Row] -> record mapping. Column-name string literals intentionally mirror the
 * AOSP `Telephony.*`, `CallLog.Calls.*` and `MediaStore.*` constants but are inlined
 * so this file has no Android dependency and every unit-of-time and column-spelling
 * gotcha from docs/phase0-findings.md is verifiable in a plain JVM unit test.
 */

private const val SECONDS_TO_MS = 1000L

object SmsMapper {
    /** `sms.date` and `date_sent` are already epoch **milliseconds** — no conversion. */
    fun map(row: Row): SmsRecord? {
        val id = row.getLong("_id") ?: return null
        return SmsRecord(
            id = id,
            address = row.getString("address"),
            dateEpochMs = row.getLong("date") ?: 0L,
            dateSentEpochMs = row.getLong("date_sent") ?: 0L,
            type = row.getInt("type") ?: 0,
            body = row.getString("body"),
            subject = row.getString("subject"),
            read = row.getInt("read") ?: 0,
            status = row.getInt("status") ?: -1,
            protocol = row.getString("protocol"),
            serviceCenter = row.getString("service_center"),
            locked = row.getInt("locked") ?: 0,
            subId = row.getInt("sub_id") ?: -1,
        )
    }
}

object MmsMapper {
    /** GOTCHA: `mms.date` is epoch **seconds** — multiply by 1000. */
    fun mapBase(row: Row, parts: List<MmsPart>, addresses: List<MmsAddr>) =
        row.getLong("_id")?.let { id ->
            com.diffuse.backup.model.MmsRecord(
                id = id,
                dateEpochMs = (row.getLong("date") ?: 0L) * SECONDS_TO_MS,
                messageBox = row.getInt("msg_box") ?: 0,
                messageType = row.getInt("m_type") ?: 0,
                contentTypeToken = row.getString("ct_t"),
                messageId = row.getString("m_id"),
                subject = row.getString("sub"),
                read = row.getInt("read") ?: 0,
                messageSize = row.getLong("m_size"),
                subId = row.getInt("sub_id") ?: -1,
                parts = parts,
                addresses = addresses,
            )
        }

    /**
     * Maps a `content://mms/part` row *without* its bytes. [isTextual] decides whether
     * this part carries inline [MmsPart.text] (`text/…` and `application/smil`) or is a
     * binary attachment whose bytes the extractor reads separately and passes in.
     */
    fun mapPart(row: Row, data: ByteArray?): MmsPart {
        val ct = row.getString("ct").orEmpty()
        return MmsPart(
            seq = row.getInt("seq") ?: 0,
            contentType = ct,
            name = row.getString("name"),
            charset = row.getString("chset"),
            contentLocation = row.getString("cl"),
            contentId = row.getString("cid"),
            text = if (isTextual(ct)) row.getString("text") else null,
            data = if (isTextual(ct)) null else data,
        )
    }

    /** True for parts whose payload lives in the `text` column, false for binaries. */
    fun isTextual(contentType: String): Boolean =
        contentType == "application/smil" || contentType.startsWith("text/")

    fun mapAddr(row: Row) = MmsAddr(
        address = row.getString("address"),
        type = row.getInt("type") ?: 0,
        charset = row.getString("charset"),
    )
}

object CallMapper {
    /** `call_log.date` is already epoch **milliseconds**; `duration` is seconds. */
    fun map(row: Row): CallRecord? {
        val id = row.getLong("_id") ?: return null
        return CallRecord(
            id = id,
            number = row.getString("number"),
            durationSec = row.getLong("duration") ?: 0L,
            dateEpochMs = row.getLong("date") ?: 0L,
            type = row.getInt("type") ?: 0,
            presentation = row.getInt("presentation") ?: 1,
            subscriptionId = row.getString("subscription_id"),
        )
    }
}

object MediaMapper {
    /**
     * GOTCHAS: the column is `datetaken` (no underscore) and is epoch **ms**, but can
     * be 0 → mapped to null; `date_added` / `date_modified` are epoch **seconds**.
     */
    fun map(row: Row, kind: MediaKind, contentUri: String): MediaRecord? {
        val id = row.getLong("_id") ?: return null
        val taken = row.getLong("datetaken")?.takeIf { it > 0L }
        return MediaRecord(
            id = id,
            kind = kind,
            displayName = row.getString("_display_name"),
            relativePath = row.getString("relative_path"),
            mimeType = row.getString("mime_type"),
            sizeBytes = row.getLong("_size") ?: 0L,
            dateTakenEpochMs = taken,
            dateAddedEpochMs = (row.getLong("date_added") ?: 0L) * SECONDS_TO_MS,
            dateModifiedEpochMs = (row.getLong("date_modified") ?: 0L) * SECONDS_TO_MS,
            generationModified = row.getLong("generation_modified") ?: 0L,
            contentUri = contentUri,
        )
    }
}
