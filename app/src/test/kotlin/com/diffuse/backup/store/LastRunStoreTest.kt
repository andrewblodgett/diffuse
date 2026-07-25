package com.diffuse.backup.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LastRunStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun returns_null_before_any_run() {
        assertNull(LastRunStore(File(tmp.root, "last.properties")).get())
    }

    @Test fun round_trips_a_success_across_instances() {
        val file = File(tmp.root, "last.properties")
        LastRunStore(file).put(LastRun(timestampMs = 1_700_000_000_000, success = true, summary = "7 SMS"))

        val read = LastRunStore(file).get()!!
        assertEquals(1_700_000_000_000, read.timestampMs)
        assertTrue(read.success)
        assertEquals("7 SMS", read.summary)
    }

    @Test fun round_trips_a_failure() {
        val file = File(tmp.root, "last.properties")
        LastRunStore(file).put(LastRun(timestampMs = 42, success = false, summary = "network error"))

        val read = LastRunStore(file).get()!!
        assertEquals(false, read.success)
        assertEquals("network error", read.summary)
    }

    @Test fun latest_put_wins() {
        val file = File(tmp.root, "last.properties")
        val store = LastRunStore(file)
        store.put(LastRun(1, true, "first"))
        store.put(LastRun(2, false, "second"))
        assertEquals("second", LastRunStore(file).get()!!.summary)
    }
}
