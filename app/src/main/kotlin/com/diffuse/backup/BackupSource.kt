package com.diffuse.backup

import com.diffuse.backup.model.CallRecord
import com.diffuse.backup.model.MediaRecord
import com.diffuse.backup.model.MmsRecord
import com.diffuse.backup.model.SmsRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only contract implemented by every backup source (SMS, MMS, call log, images,
 * video). Every run exports each source in full; there is no incremental selection.
 *
 * READ-ONLY INVARIANT: Diffuse never mutates on-device data, and this interface
 * enforces that *by construction*: it exposes only read operations. Implementations
 * open content-provider cursors and media streams in read ("r") mode exclusively.
 *
 * The invariant is guarded in three layers:
 *   1. Manifest: declares no write/manage data permission and never requests the
 *      default-SMS role, so the OS throws SecurityException on any write attempt.
 *   2. Types: this interface offers no write surface for callers to reach for.
 *   3. CI: scripts/check-readonly.sh fails the build if a provider-mutating call
 *      (insert/update/delete, MediaStore write-requests, an output stream, a non-"r"
 *      file mode, ...) ever appears in app source.
 */
interface BackupSource<T> {

    /**
     * How many rows [items] is expected to emit. Used only to size progress bars: the
     * provider can change between this query and the read, so documents count what they
     * actually wrote instead of trusting this number.
     */
    fun count(): Int

    /** Emit every record. Reads cursors (and streams) only; never writes to the provider. */
    fun items(): Flow<T>
}

/** The five sources one backup run reads, bundled so tests can substitute fakes. */
data class BackupSources(
    val calls: BackupSource<CallRecord>,
    val sms: BackupSource<SmsRecord>,
    val mms: BackupSource<MmsRecord>,
    val images: BackupSource<MediaRecord>,
    val videos: BackupSource<MediaRecord>,
) {
    /** Factories live next to the Android implementations (see `ContentProviderSource.kt`). */
    companion object
}
