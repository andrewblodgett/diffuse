package com.diffuse.backup.format

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveDocumentBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private suspend fun build(headerTag: String, records: List<String>) =
        ArchiveDocumentBuilder(tmp.root).build(
            name = "doc.xml",
            header = { out, count -> out.append("<root tag=\"$headerTag\" count=\"$count\">\n") },
            footer = { out -> out.append("</root>\n") },
        ) { out ->
            records.forEach { out.append("  <r v=\"$it\" />\n") }
            records.size
        }

    @Test fun header_carries_the_count_actually_written() = runTest {
        val doc = build("x", listOf("a", "b", "c"))!!
        assertEquals(3, doc.recordCount)
        val text = doc.file.readText()
        assertTrue(text.startsWith("<root tag=\"x\" count=\"3\">\n  <r v=\"a\" />"))
        assertTrue(text.endsWith("</root>\n"))
    }

    @Test fun fingerprint_ignores_the_header_but_tracks_the_records() = runTest {
        val first = build("run-1", listOf("a", "b"))!!.fingerprint
        val sameRecordsNewRun = build("run-2", listOf("a", "b"))!!.fingerprint
        val changed = build("run-3", listOf("a", "c"))!!.fingerprint
        assertEquals(first, sameRecordsNewRun)
        assertNotEquals(first, changed)
    }

    @Test fun no_records_means_no_document_and_no_leftovers() = runTest {
        assertNull(build("x", emptyList()))
        assertTrue(tmp.root.list()!!.isEmpty())
    }

    @Test fun the_temporary_body_file_is_removed() = runTest {
        build("x", listOf("a"))
        assertEquals(listOf("doc.xml"), tmp.root.list()!!.toList())
    }
}
