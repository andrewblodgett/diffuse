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
        assertEquals(1_609_459_200_000L, r.updatedAtEpochMs)
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

    @Test fun mms_part_classification_text_vs_binary() {
        assertTrue(MmsMapper.isTextual("text/plain"))
        assertTrue(MmsMapper.isTextual("application/smil"))
        assertFalse(MmsMapper.isTextual("image/jpeg"))

        val textPart = MmsMapper.mapPart(
            FakeRow(mapOf("seq" to 0, "ct" to "text/plain", "text" to "hello")),
            data = byteArrayOf(1, 2, 3), // should be ignored for a text part
        )
        assertEquals("hello", textPart.text)
        assertNull(textPart.data)

        val binaryPart = MmsMapper.mapPart(
            FakeRow(mapOf("seq" to 1, "ct" to "image/jpeg", "text" to "ignored")),
            data = byteArrayOf(1, 2, 3),
        )
        assertNull(binaryPart.text)
        assertEquals(3, binaryPart.data!!.size)
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
        assertEquals("images", r.sourceId)
    }

    @Test fun media_datetaken_zero_becomes_null() {
        val r = MediaMapper.map(
            FakeRow(mapOf("_id" to 1L, "datetaken" to 0L, "date_modified" to 5L)),
            kind = MediaKind.VIDEO,
            contentUri = "u",
        )!!
        assertNull(r.dateTakenEpochMs)
        assertEquals(5_000L, r.updatedAtEpochMs) // falls back to date_modified
    }
}
