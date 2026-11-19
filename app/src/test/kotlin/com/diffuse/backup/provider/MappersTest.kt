package com.diffuse.backup.provider

import com.diffuse.backup.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the every-gotcha mapping logic from docs/phase0-findings.md. */
class MappersTest {

    @Test fun sms_date_is_treated_as_milliseconds() {
        val r = SmsMapper.map(FakeRow(mapOf("_id" to 5L, "date" to 1_609_459_200_000L, "type" to 1)))!!
        assertEquals(5L, r.id)
        assertEquals(1_609_459_200_000L, r.dateEpochMs) // unchanged: already ms
    }

    @Test fun sms_missing_id_maps_to_null() {
        assertNull(SmsMapper.map(FakeRow(mapOf("date" to 1L))))
    }

    @Test fun mms_date_seconds_are_converted_to_milliseconds() {
        val r = MmsMapper.mapBase(
            FakeRow(mapOf("_id" to 9L, "date" to 1_609_459_200L, "msg_box" to 1, "m_type" to 132)),
            parts = emptyList(),
            addresses = emptyList(),
        )!!
        assertEquals(1_609_459_200_000L, r.dateEpochMs) // seconds * 1000
        assertEquals(132, r.messageType)
        assertEquals(1, r.messageBox)
    }

    @Test fun mms_part_uses_text_column_when_present_and_skips_reading_bytes() {
        var bytesRead = false
        val textPart = MmsMapper.mapPart(FakeRow(mapOf("seq" to 0, "ct" to "text/plain", "text" to "hello"))) {
            bytesRead = true; byteArrayOf(1)
        }
        assertEquals("hello", textPart.text)
        assertNull(textPart.data)
        assertFalse(bytesRead)
    }

    @Test fun mms_binary_part_reads_bytes() {
        val binaryPart = MmsMapper.mapPart(FakeRow(mapOf("seq" to 1, "ct" to "image/jpeg"))) { byteArrayOf(1, 2, 3) }
        assertNull(binaryPart.text)
        assertEquals(3, binaryPart.data!!.size)
    }

    @Test fun mms_vcard_part_is_read_from_its_file_not_dropped() {
        // AOSP stores text/x-vCard in a file, leaving the text column null. Deciding by the
        // "text/" prefix used to emit text="null" and lose the attachment entirely.
        val vcard = "BEGIN:VCARD\nEND:VCARD".toByteArray()
        val part = MmsMapper.mapPart(FakeRow(mapOf("seq" to 2, "ct" to "text/x-vCard"))) { vcard }
        assertNull(part.text)
        assertTrue(vcard.contentEquals(part.data))
    }

    @Test fun call_date_is_milliseconds_and_duration_seconds() {
        val r = CallMapper.map(FakeRow(mapOf(
            "_id" to 3L, "number" to "555", "duration" to 60L, "date" to 1_609_459_200_000L, "type" to 2,
        )))!!
        assertEquals(1_609_459_200_000L, r.dateEpochMs)
        assertEquals(60L, r.durationSec)
        assertEquals(2, r.type)
    }

    @Test fun media_datetaken_ms_passthrough_and_added_modified_seconds_converted() {
        val r = MediaMapper.map(
            FakeRow(mapOf(
                "_id" to 1L,
                "datetaken" to 1_609_459_200_000L, // already ms
                "date_added" to 1_609_459_200L,     // seconds
                "date_modified" to 1_609_459_260L,  // seconds
                "generation_modified" to 42L,
            )),
            kind = MediaKind.IMAGE,
            contentUri = "content://media/external/images/media/1",
        )!!
        assertEquals(1_609_459_200_000L, r.dateTakenEpochMs)
        assertEquals(1_609_459_200_000L, r.dateAddedEpochMs)   // *1000
        assertEquals(1_609_459_260_000L, r.dateModifiedEpochMs) // *1000
        assertEquals(42L, r.generationModified)
        assertEquals("images:1", r.stableId)
    }

    @Test fun media_datetaken_zero_becomes_null() {
        val r = MediaMapper.map(
            FakeRow(mapOf("_id" to 1L, "datetaken" to 0L, "date_modified" to 5L)),
            kind = MediaKind.VIDEO,
            contentUri = "u",
        )!!
        assertNull(r.dateTakenEpochMs)
        assertEquals(5_000L, r.dateModifiedEpochMs)
    }
}
