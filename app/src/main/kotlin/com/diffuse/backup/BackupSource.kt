package com.diffuse.backup

import com.diffuse.backup.model.BackupItem
import kotlinx.coroutines.flow.Flow

/**
 * Read-only contract implemented by every backup source (SMS, MMS, call log,
 * images, video).
 *
 * READ-ONLY INVARIANT — Diffuse never mutates on-device data, and this interface
 * enforces that *by construction*: it exposes only read operations. There is no
 * insert/update/delete method anywhere in the type, and implementations must open
 * content-provider cursors and media streams in read ("r") mode exclusively.
 *
 * The invariant is guarded in three layers:
 *   1. Manifest — declares no write/manage data permission and never requests the
 *      default-SMS role, so the OS throws SecurityException on any write attempt.
 *   2. Types — this interface offers no write surface for callers to reach for.
 *   3. CI — scripts/check-readonly.sh fails the build if a provider-mutating call
 *      (contentResolver.insert/update/delete, MediaStore write-requests, an output
 *      stream, a non-"r" file mode, ...) ever appears in app source.
 *
 * @param C an opaque, monotonic change-token type (e.g. a MediaStore generation value,
 *          or a max row `date`). Passing a previous token to [itemsSince] yields only
 *          items added or changed since then; passing null yields everything.
 */
interface BackupSource<C : Any> {

    /** Stable identifier for this source, e.g. "sms", "call_log", "images". */
    val id: String

    /**
     * Emit every [BackupItem] added or modified strictly after [since]
     * (or all items when [since] is null). Read-only: opens cursors and streams
     * for reading and never writes back to the provider.
     */
    fun itemsSince(since: C?): Flow<BackupItem>
}
