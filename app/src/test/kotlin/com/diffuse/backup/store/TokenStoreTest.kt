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

    @Test fun clear_removes_token_and_persists() {
        val file = File(tmp.root, "state.properties")
        FilePropertiesTokenStore(file).apply {
            put("sms", 1L)
            put("mms", 2L)
            clear("sms")
        }
        val reopened = FilePropertiesTokenStore(file)
        assertNull(reopened.get("sms"))
        assertEquals(2L, reopened.get("mms"))
    }

    @Test fun clear_of_unknown_source_is_a_no_op() {
        val store = FilePropertiesTokenStore(File(tmp.root, "state.properties"))
        store.clear("sms") // must not throw
        assertNull(store.get("sms"))
    }
}
