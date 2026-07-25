package com.diffuse.backup.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TokenStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unknown_source_returns_null() {
        val store = FilePropertiesTokenStore(File(tmp.root, "state.properties"))
        assertNull(store.get("sms"))
    }

    @Test fun tokens_round_trip_and_survive_reopen() {
        val file = File(tmp.root, "nested/state.properties")
        FilePropertiesTokenStore(file).apply {
            put("sms", 1_609_459_200_000L)
            put("mms", 1_609_459_200L) // seconds-native token
        }
        // Reopen from disk: a fresh instance must read the persisted values.
        val reopened = FilePropertiesTokenStore(file)
        assertEquals(1_609_459_200_000L, reopened.get("sms"))
        assertEquals(1_609_459_200L, reopened.get("mms"))
    }
}
