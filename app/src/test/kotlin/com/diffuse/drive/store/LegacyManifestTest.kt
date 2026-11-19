package com.diffuse.drive.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyManifestTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun reads_only_media_paths_from_a_0_1_manifest() {
        val file = File(tmp.root, "upload-manifest.properties")
        file.writeText("media/DCIM/Camera/a.jpg=ID1\nsms.xml=ID2\ncalls.xml=ID3\n")
        assertEquals(mapOf("media/DCIM/Camera/a.jpg" to "ID1"), LegacyManifest(file).mediaPaths())
    }

    @Test fun missing_or_corrupt_file_reads_as_empty() {
        val file = File(tmp.root, "upload-manifest.properties")
        assertTrue(LegacyManifest(file).mediaPaths().isEmpty())
        file.writeText("bad=\\u12")
        assertTrue(LegacyManifest(file).mediaPaths().isEmpty())
    }

    @Test fun retire_deletes_it() {
        val file = File(tmp.root, "upload-manifest.properties").apply { writeText("media/a=1") }
        LegacyManifest(file).retire()
        assertFalse(file.exists())
    }
}
