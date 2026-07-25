package com.diffuse.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

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
        assertEquals(2, d.hourOfDay)
        assertEquals(Calendar.MONDAY, d.dayOfWeek)
        assertTrue(d.backupPictures && d.backupVideos && d.backupMessages)
    }

    private val oneDayMs = 24 * 3600_000L

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute) }.timeInMillis

    private fun fireTime(nowMs: Long, delay: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = nowMs + delay }

    @Test fun daily_delay_rolls_to_tomorrow_when_hour_already_passed() {
        val now = at(2026, Calendar.JULY, 25, 10, 30) // 10:30, target 02:00 already gone today
        val delay = BackupScheduler.initialDelayMillis(BackupPrefs(BackupFrequency.Daily, hourOfDay = 2), now)
        assertTrue(delay > 0 && delay <= oneDayMs)
        val fire = fireTime(now, delay)
        assertEquals(2, fire.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, fire.get(Calendar.MINUTE))
    }

    @Test fun daily_delay_is_later_today_when_hour_still_ahead() {
        val now = at(2026, Calendar.JULY, 25, 10, 30) // 10:30, target 14:00 still ahead
        val delay = BackupScheduler.initialDelayMillis(BackupPrefs(BackupFrequency.Daily, hourOfDay = 14), now)
        assertTrue(delay > 0 && delay < oneDayMs)
        val fire = fireTime(now, delay)
        assertEquals(14, fire.get(Calendar.HOUR_OF_DAY))
        assertEquals(now / oneDayMs, (now + delay) / oneDayMs) // same UTC day bucket ≈ today
    }

    @Test fun weekly_delay_lands_on_chosen_day_and_hour_within_a_week() {
        val now = at(2026, Calendar.JULY, 25, 10, 30)
        val prefs = BackupPrefs(BackupFrequency.Weekly, hourOfDay = 3, dayOfWeek = Calendar.MONDAY)
        val delay = BackupScheduler.initialDelayMillis(prefs, now)
        assertTrue(delay > 0 && delay <= 7 * oneDayMs)
        val fire = fireTime(now, delay)
        assertEquals(Calendar.MONDAY, fire.get(Calendar.DAY_OF_WEEK))
        assertEquals(3, fire.get(Calendar.HOUR_OF_DAY))
    }
}
