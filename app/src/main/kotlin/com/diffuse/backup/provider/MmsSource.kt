package com.diffuse.backup.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.Telephony
import com.diffuse.backup.BackupSource
import com.diffuse.backup.model.MmsAddr
import com.diffuse.backup.model.MmsPart
import com.diffuse.backup.model.MmsRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Extracts MMS via the three-table join the provider requires (docs/phase0-findings.md):
 * the base `content://mms` row, its `content://mms/part` rows, and its
 * `content://mms/{id}/addr` participants. A part whose payload is not in its `text`
 * column has its bytes read from `content://mms/part/{partId}` as an input stream
 * (READ-ONLY) and carried on the part, so the writer can base64-encode them.
 */
class MmsSource(private val resolver: ContentResolver) : BackupSource<MmsRecord> {

    override fun items(): Flow<MmsRecord> = flow {
        resolver.forEachRow(MMS_URI, BASE_PROJECTION, null, null, "date ASC") { baseRow ->
            val mmsId = baseRow.getLong("_id")
            if (mmsId != null) {
                val record = MmsMapper.mapBase(baseRow, readParts(mmsId), readAddrs(mmsId))
                if (record != null) emit(record)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun count(): Int = resolver.countRows(MMS_URI)

    private fun readParts(mmsId: Long): List<MmsPart> {
        val parts = ArrayList<MmsPart>()
        resolver.forEachRow(
            PART_URI, PART_PROJECTION, "mid = ?", arrayOf(mmsId.toString()), "seq ASC",
        ) { row ->
            parts.add(MmsMapper.mapPart(row) { row.getLong("_id")?.let(::readPartBytes) })
        }
        return parts
    }

    /** Reads one part's file payload; null (not fatal) if the part has no readable file. */
    private fun readPartBytes(partId: Long): ByteArray? = try {
        resolver.openInputStream(ContentUris.withAppendedId(PART_URI, partId))?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun readAddrs(mmsId: Long): List<MmsAddr> {
        val addrs = ArrayList<MmsAddr>()
        val addrUri = MMS_URI.buildUpon().appendPath(mmsId.toString()).appendPath("addr").build()
        resolver.forEachRow(addrUri, ADDR_PROJECTION, null, null, null) { row ->
            addrs.add(MmsMapper.mapAddr(row))
        }
        return addrs
    }

    private companion object {
        val MMS_URI: Uri = Telephony.Mms.CONTENT_URI
        val PART_URI: Uri = Uri.parse("content://mms/part")

        val BASE_PROJECTION = arrayOf(
            "_id", "date", "msg_box", "m_type", "ct_t", "m_id", "sub", "read", "m_size", "sub_id",
        )
        val PART_PROJECTION = arrayOf(
            "_id", "seq", "ct", "name", "chset", "cl", "cid", "text",
        )
        val ADDR_PROJECTION = arrayOf("address", "type", "charset")
    }
}
