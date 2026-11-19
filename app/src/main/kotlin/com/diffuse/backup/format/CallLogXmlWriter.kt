package com.diffuse.backup.format

import com.diffuse.backup.model.CallRecord

/**
 * Writes a SMS Backup & Restore `calls-*.xml` document (`<calls>` root of `<call>`
 * elements). `call.date` is epoch **ms** (provider-native), `duration` is seconds.
 * Stateless apart from the run's header values, so the header, records and footer can
 * go to different [Appendable]s (see [ArchiveDocumentBuilder]).
 */
class CallLogXmlWriter(
    private val backupDateMs: Long,
    private val backupSet: String,
) {
    fun header(out: Appendable, count: Int) {
        out.append(Xml.DECLARATION).append('\n')
        out.append("<?xml-stylesheet type=\"text/xsl\" href=\"calls.xsl\"?>").append('\n')
        val header = StringBuilder("<calls")
            .attr("count", count)
            .attr("backup_set", backupSet)
            .attr("backup_date", backupDateMs)
            .attr("type", "full")
            .append('>')
        out.append(header).append('\n')
    }

    fun call(out: Appendable, r: CallRecord) {
        val sb = StringBuilder("  <call")
            .attr("number", r.number)
            .attr("duration", r.durationSec)
            .attr("date", r.dateEpochMs)
            .attr("type", r.type)
            .attr("presentation", r.presentation)
            .attr("subscription_id", r.subscriptionId)
            .attr("readable_date", ReadableDate.format(r.dateEpochMs))
            .attr("contact_name", UNKNOWN_CONTACT)
            .append(" />")
        out.append(sb).append('\n')
    }

    fun footer(out: Appendable) {
        out.append("</calls>").append('\n')
    }
}
