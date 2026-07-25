package com.diffuse.backup.format

import com.diffuse.backup.model.MmsRecord
import com.diffuse.backup.model.SmsRecord
import java.util.Base64

/**
 * Streams a single SMS Backup & Restore `sms-*.xml` document — the `<smses>` root that
 * holds both `<sms>` and `<mms>` elements — to an [Appendable] (one message at a time,
 * so MMS attachment bytes never all sit in memory at once).
 *
 * Restore-compat details that bite if wrong:
 *  - `<sms date>` is epoch **ms** (provider-native); `<mms date>` is epoch **seconds**
 *    (provider-native) — restore writes each attribute straight back into its column,
 *    so we emit seconds for MMS by dividing the record's ms back down.
 *  - binary MMS parts carry their bytes base64-encoded in the `data` attribute; text
 *    and SMIL parts carry `text` instead (the SMIL layout part is kept, not discarded).
 */
class SmsBackupXmlWriter(
    private val out: Appendable,
    private val backupDateMs: Long,
    private val backupSet: String,
) {
    fun start(count: Int) {
        out.append(Xml.DECLARATION).append('\n')
        out.append("<?xml-stylesheet type=\"text/xsl\" href=\"sms.xsl\"?>").append('\n')
        val header = StringBuilder("<smses")
            .attr("count", count)
            .attr("backup_set", backupSet)
            .attr("backup_date", backupDateMs)
            .attr("type", "full")
            .append('>')
        out.append(header).append('\n')
    }

    fun writeSms(r: SmsRecord) {
        val sb = StringBuilder("  <sms")
            .attr("protocol", r.protocol)
            .attr("address", r.address)
            .attr("date", r.dateEpochMs)
            .attr("type", r.type)
            .attr("subject", r.subject)
            .attr("body", r.body)
            .attr("toa", Xml.NULL)
            .attr("sc_toa", Xml.NULL)
            .attr("service_center", r.serviceCenter)
            .attr("read", r.read)
            .attr("status", r.status)
            .attr("locked", r.locked)
            .attr("date_sent", r.dateSentEpochMs)
            .attr("sub_id", r.subId)
            .attr("readable_date", ReadableDate.format(r.dateEpochMs))
            .attr("contact_name", UNKNOWN_CONTACT)
            .append(" />")
        out.append(sb).append('\n')
    }

    fun writeMms(r: MmsRecord) {
        // MMS provider stores `date` in seconds; convert the record's ms back down.
        val dateSeconds = r.dateEpochMs / 1000L
        val open = StringBuilder("  <mms")
            .attr("date", dateSeconds)
            .attr("ct_t", r.contentTypeToken)
            .attr("msg_box", r.messageBox)
            .attr("rr", Xml.NULL)
            .attr("sub", r.subject)
            .attr("read_status", Xml.NULL)
            .attr("address", primaryAddress(r))
            .attr("m_id", r.messageId)
            .attr("read", r.read)
            .attr("m_size", r.messageSize?.toString())
            .attr("m_type", r.messageType)
            .attr("sub_id", r.subId)
            .attr("readable_date", ReadableDate.format(r.dateEpochMs))
            .attr("contact_name", UNKNOWN_CONTACT)
            .append('>')
        out.append(open).append('\n')

        out.append("    <parts>").append('\n')
        for (part in r.parts) {
            val text = if (part.data == null) part.text else null
            val data = part.data?.let { Base64.getEncoder().encodeToString(it) }
            val p = StringBuilder("      <part")
                .attr("seq", part.seq)
                .attr("ct", part.contentType)
                .attr("name", part.name)
                .attr("chset", part.charset)
                .attr("cd", Xml.NULL)
                .attr("fn", Xml.NULL)
                .attr("cid", part.contentId)
                .attr("cl", part.contentLocation)
                .attr("ctt_s", Xml.NULL)
                .attr("ctt_t", Xml.NULL)
                .attr("text", text)
                .attr("data", data)
                .append(" />")
            out.append(p).append('\n')
        }
        out.append("    </parts>").append('\n')

        out.append("    <addrs>").append('\n')
        for (addr in r.addresses) {
            val a = StringBuilder("      <addr")
                .attr("address", addr.address)
                .attr("type", addr.type)
                .attr("charset", addr.charset)
                .append(" />")
            out.append(a).append('\n')
        }
        out.append("    </addrs>").append('\n')

        out.append("  </mms>").append('\n')
    }

    fun finish() {
        out.append("</smses>").append('\n')
    }

    /** The From participant (type 137), else the first address, for the `<mms address>` hint. */
    private fun primaryAddress(r: MmsRecord): String? =
        (r.addresses.firstOrNull { it.type == 137 } ?: r.addresses.firstOrNull())?.address
}
