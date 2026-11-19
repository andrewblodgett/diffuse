package com.diffuse.backup.provider

import android.content.ContentResolver
import android.net.Uri
import com.diffuse.backup.BackupSource
import com.diffuse.backup.BackupSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Base for every extractor whose source is a single provider table: query it with
 * [projection], sorted by [sortColumn], and [map] each row to a record.
 *
 * READ-ONLY: emits by reading a cursor via [forEachRow]; no write path exists.
 */
abstract class ContentProviderSource<T>(
    protected val resolver: ContentResolver,
    protected val uri: Uri,
    private val projection: Array<String>,
    private val sortColumn: String,
) : BackupSource<T> {

    /** Map one row to a record, or null to skip it. */
    protected abstract fun map(row: Row): T?

    override fun items(): Flow<T> = flow {
        resolver.forEachRow(uri, projection, null, null, "$sortColumn ASC") { row ->
            map(row)?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override fun count(): Int = resolver.countRows(uri)
}

/** The production sources, all reading through [resolver]. */
fun BackupSources.Companion.from(resolver: ContentResolver) = BackupSources(
    calls = CallLogSource(resolver),
    sms = SmsSource(resolver),
    mms = MmsSource(resolver),
    images = MediaSource.images(resolver),
    videos = MediaSource.video(resolver),
)
