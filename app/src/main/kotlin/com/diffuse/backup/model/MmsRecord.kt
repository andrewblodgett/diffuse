package com.diffuse.backup.model


/**
 * One MMS message from `content://mms`, assembled from the three-table join the
 * provider requires: the base row, its `content://mms/part` rows, and its
 * `content://mms/{id}/addr` rows (see docs/phase0-findings.md).
 *
 * GOTCHA: `mms.date` is epoch **seconds** (10 digits); [dateEpochMs] is already
 * converted to ms here.
 */
data class MmsRecord(
    val id: Long,
    /** epoch ms, converted from the provider's seconds. */
    val dateEpochMs: Long,
    /** msg_box: 1=received, 2=sent, 3=draft, 4=outbox. */
    val messageBox: Int,
    /** m_type: 128=send-req (sent), 132=retrieve-conf (received), ... */
    val messageType: Int,
    /** ct_t, e.g. "application/vnd.wap.multipart.related". */
    val contentTypeToken: String?,
    /** m_id, the carrier message id. */
    val messageId: String?,
    /** sub, the MMS subject. */
    val subject: String?,
    val read: Int,
    /** m_size in bytes, null when absent. */
    val messageSize: Long?,
    val subId: Int,
    val parts: List<MmsPart>,
    val addresses: List<MmsAddr>,
) : BackupItem {
    override val sourceId: String get() = "mms"
    override val stableId: String get() = id.toString()
    override val updatedAtEpochMs: Long get() = dateEpochMs
}

/**
 * One `content://mms/part` row. Exactly one of [text] / [data] is populated:
 * text for `text/…` and `application/smil` parts, raw bytes for binary attachments
 * (the writer base64-encodes [data] into the `data` attribute). The SMIL layout
 * part is *kept*, not discarded — restore-compatibility needs it.
 */
data class MmsPart(
    val seq: Int,
    /** ct, the part content type. */
    val contentType: String,
    val name: String?,
    /** chset, character set. */
    val charset: String?,
    /** cl, content location. */
    val contentLocation: String?,
    /** cid, content id. */
    val contentId: String?,
    val text: String?,
    val data: ByteArray?,
) {
    // data class + ByteArray: identity-based equality is fine here (records are
    // never compared for equality), but override to avoid surprising callers.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmsPart) return false
        return seq == other.seq && contentType == other.contentType &&
            name == other.name && charset == other.charset &&
            contentLocation == other.contentLocation && contentId == other.contentId &&
            text == other.text && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = seq
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * One `content://mms/{id}/addr` participant.
 * type: 129=BCC, 130=CC, 137=From, 151=To.
 */
data class MmsAddr(
    val address: String?,
    val type: Int,
    val charset: String?,
)
