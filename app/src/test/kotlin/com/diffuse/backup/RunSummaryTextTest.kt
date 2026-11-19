package com.diffuse.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class RunSummaryTextTest {

    @Test fun describes_counts_with_new_media_called_out() {
        val summary = BackupSummary(
            calls = 412, sms = 7_286, mms = 2_791,
            photos = MediaTally(inLibrary = 351, uploaded = 3, alreadyBackedUp = 348),
            videos = MediaTally(inLibrary = 9, alreadyBackedUp = 9),
        )
        assertEquals(
            "412 calls, 10,077 messages, 351 photos (3 new), 9 videos",
            RunSummaryText.describe(summary, BackupContent()),
        )
    }

    @Test fun mentions_failures_and_permission_skips() {
        val summary = BackupSummary(photos = MediaTally(inLibrary = 5, uploaded = 4, failed = 1))
        val text = RunSummaryText.describe(
            summary,
            included = BackupContent(pictures = true, videos = false, messages = false),
            skippedForPermission = BackupContent(pictures = false, videos = true, messages = true),
        )
        assertEquals("4 photos (4 new); 1 item failed, will retry; skipped messages & calls, videos: permission needed", text)
    }
}
