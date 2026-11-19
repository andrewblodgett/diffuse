package com.diffuse.util

import com.diffuse.backup.MediaUploadAbortedException
import com.diffuse.drive.DriveHttpException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CausesTest {

    @Test fun finds_a_root_cause_through_a_wrapper() {
        val full = DriveHttpException(403, "storageQuotaExceeded", "Drive is full")
        val aborted = MediaUploadAbortedException("photos", 3, full)
        assertSame(full, aborted.causeOfType<DriveHttpException>())
        assertTrue(aborted.causeOfType<DriveHttpException>()!!.isStorageFull)
    }

    @Test fun matches_the_exception_itself_and_returns_null_when_absent() {
        val e = IllegalStateException("x")
        assertSame(e, e.causeOfType<IllegalStateException>())
        assertNull(e.causeOfType<DriveHttpException>())
    }
}
