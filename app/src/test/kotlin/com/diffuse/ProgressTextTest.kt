package com.diffuse

import com.diffuse.backup.BackupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTextTest {

    @Test fun media_stages_show_a_count_and_percent() {
        val line = RunProgress(stage = BackupStage.Photos, photoDone = 12, photoTotal = 351).describe()
        assertEquals("Backing up photos: 12 of 351", line.text)
        assertEquals(3, line.percent)
    }

    @Test fun done_never_exceeds_total_when_the_library_grows_mid_run() {
        val line = RunProgress(stage = BackupStage.Videos, videoDone = 11, videoTotal = 10).describe()
        assertEquals("Backing up videos: 10 of 10", line.text)
    }

    @Test fun text_stages_have_no_count() {
        val line = RunProgress(stage = BackupStage.Messages).describe()
        assertEquals("Backing up messages…", line.text)
        assertNull(line.percent)
    }
}
