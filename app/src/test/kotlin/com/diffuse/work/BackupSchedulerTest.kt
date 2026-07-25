package com.diffuse.work

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSchedulerTest {
    @Test fun daily_is_24h_and_weekly_is_a_week() {
        assertEquals(24, BackupScheduler.intervalHours(BackupFrequency.Daily))
        assertEquals(24 * 7, BackupScheduler.intervalHours(BackupFrequency.Weekly))
    }

    @Test fun default_prefs_match_the_phase4_decision() {
        val d = BackupPrefs()
        assertEquals(BackupFrequency.Daily, d.frequency)
        assertEquals(true, d.wifiOnly)
        assertEquals(true, d.chargingOnly)
    }
}
