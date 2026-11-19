package com.diffuse.drive.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentUploadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun journal() = RecentUploads(File(tmp.root, "recent.tsv"), now = { now }, windowMs = 100)

    @Test fun records_survive_a_new_instance_and_expire_after_the_window() {
        val entry = RecentUploads.Entry("k", "id", "a.jpg", "4", origin = "o", folder = "media/DCIM")
        journal().record(entry)
        assertEquals(listOf(entry), journal().load())

        now += 101
        assertTrue(journal().load().isEmpty())
        assertEquals("expired lines are pruned from the file", "", File(tmp.root, "recent.tsv").readText())
    }

    @Test fun a_line_cut_short_by_a_crash_is_ignored() {
        journal().record(RecentUploads.Entry("k1", "id1", "a", "1"))
        File(tmp.root, "recent.tsv").appendText("${now}\tk2\tid2") // torn write
        assertEquals(listOf("k1"), journal().load().map { it.key })
    }

    @Test fun tabs_and_newlines_in_names_cannot_break_the_format() {
        journal().record(RecentUploads.Entry("k", "id", "we\tird\nname.jpg", "1"))
        assertEquals("we ird name.jpg", journal().load().single().name)
    }

    @Test fun clear_forgets_everything() {
        journal().record(RecentUploads.Entry("k", "id", "a", "1"))
        journal().clear()
        assertTrue(journal().load().isEmpty())
    }
}
