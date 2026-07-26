package com.diffuse.backup.provider

import android.content.ContentResolver
import android.net.Uri
import com.diffuse.backup.model.BackupItem
import com.diffuse.backup.BackupSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Base for every extractor whose source is a single provider table ordered by one
 * monotonic Long column (`date`, or MediaStore's `generation_modified`). The change
 * token [C] is that column's value in its **native units** — incremental runs pass
 * the last-seen value and select `column > since`. Ms conversions happen only when
 * building records, never on the token, so the WHERE clause can't drift out of unit.
 *
 * READ-ONLY: emits by reading a cursor via [forEachRow]; no write path exists.
 */
abstract class ContentProviderSource(
    protected val resolver: ContentResolver,
    protected val uri: Uri,
    private val projection: Array<String>,
    private val tokenColumn: String,
) : BackupSource<Long> {

    /** Map one row to a record, or null to skip it. */
    protected abstract fun map(row: Row): BackupItem?

    private fun sinceSelection(since: Long?): Pair<String?, Array<String>?> =
        if (since == null) null to null
        else "$tokenColumn > ?" to arrayOf(since.toString())

    override fun itemsSince(since: Long?): Flow<BackupItem> = flow {
        val (selection, args) = sinceSelection(since)
        resolver.forEachRow(uri, projection, selection, args, "$tokenColumn ASC") { row ->
            map(row)?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /** How many items an [itemsSince] with the same [since] would emit. */
    open suspend fun countSince(since: Long?): Int {
        val (selection, args) = sinceSelection(since)
        return resolver.countRows(uri, selection, args)
    }
}
