package com.diffuse.backup.format

import com.diffuse.backup.model.MmsAddr
import com.diffuse.backup.model.MmsPart
import com.diffuse.backup.model.MmsRecord
import com.diffuse.backup.model.SmsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlWriterTest {

    @Test fun escapeAttr_escapes_the_five_entities_and_drops_illegal_controls() {
        assertEquals(
            "a &amp; b &lt; c &gt; d &quot; e &apos; f",
            Xml.escapeAttr("a & b < c > d \" e ' f"),
        )
        assertTrue(Xml.escapeAttr("line1\nline2").contains("&#10;"))
        // A C0 control char (illegal in XML 1.0) is dropped, not emitted raw or numeric.
        val withControl = "x" + 1.toChar() + "y"
        assertEquals("xy", Xml.escapeAttr(withControl))
    }

    @Test fun sms_element_uses_ms_date_and_expected_attributes() {
        val sb = StringBuilder()
        SmsBackupXmlWriter(sb, backupDateMs = 0, backupSet = "set").apply {
            start(1)
            writeSms(SmsRecord(
                id = 1, address = "+15551234567", dateEpochMs = 1_609_459_200_000L,
                dateSentEpochMs = 0, type = 1, body = "hi", subject = null, read = 1,
                status = -1, protocol = "0", serviceCenter = null, locked = 0, subId = -1,
            ))
            finish()
        }
        val xml = sb.toString()
        assertTrue(xml.contains("<smses"))
        assertTrue(xml.contains("date=\"1609459200000\"")) // ms, unchanged
        assertTrue(xml.contains("address=\"+15551234567\""))
        assertTrue(xml.contains("body=\"hi\""))
        assertTrue(xml.contains("subject=\"null\"")) // absent -> literal null
        assertTrue(xml.trimEnd().endsWith("</smses>"))
    }

    @Test fun mms_date_is_seconds_and_binary_part_is_base64_smil_is_kept() {
        val record = MmsRecord(
            id = 7, dateEpochMs = 1_609_459_200_000L, messageBox = 1, messageType = 132,
            contentTypeToken = "application/vnd.wap.multipart.related", messageId = "m1",
            subject = null, read = 1, messageSize = 1234, subId = -1,
            parts = listOf(
                MmsPart(seq = -1, contentType = "application/smil", name = null, charset = null,
                    contentLocation = null, contentId = null, text = "<smil/>", data = null),
                MmsPart(seq = 0, contentType = "text/plain", name = null, charset = "106",
                    contentLocation = null, contentId = null, text = "caption", data = null),
                MmsPart(seq = 1, contentType = "image/jpeg", name = "p.jpg", charset = null,
                    contentLocation = "p.jpg", contentId = "<p>", text = null,
                    data = "abc".toByteArray()),
            ),
            addresses = listOf(MmsAddr("+15550000000", 137, "106")),
        )
        val sb = StringBuilder()
        SmsBackupXmlWriter(sb, backupDateMs = 0, backupSet = "set").apply {
            start(1); writeMms(record); finish()
        }
        val xml = sb.toString()
        assertTrue(xml.contains("date=\"1609459200\"")) // SECONDS, not ms
        assertTrue(xml.contains("m_type=\"132\""))
        assertTrue(xml.contains("ct=\"application/smil\"")) // SMIL part kept
        assertTrue(xml.contains("text=\"caption\""))
        assertTrue(xml.contains("data=\"YWJj\"")) // base64("abc")
        // SMIL text goes in text=, never in data=
        assertFalse(xml.contains("data=\"<smil/>\""))
        assertTrue(xml.contains("<addr address=\"+15550000000\" type=\"137\""))
    }
}
