package com.ruanghari.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Membungkus data source HTTP agar stream YouTube yang mewajibkan Range terbatas
 * (requireBoundedRange / useRangeChunks pada InnerTubeX) bisa dibaca utuh oleh ExoPlayer.
 *
 * ExoPlayer membuka satu DataSpec tanpa batas panjang. Kelas ini meminta server per potongan
 * (chunk) dan menyambungkannya secara transparan. Tanpa ChunkInfo di DataSpec.customData,
 * semua panggilan diteruskan apa adanya.
 */
@UnstableApi
class ChunkedDataSource(factory: DataSource.Factory) : DataSource {
    class ChunkInfo(val chunkSize: Long, val contentLength: Long)

    private val inner: DataSource = factory.createDataSource()

    private var info: ChunkInfo? = null
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var endPosition = UNSET
    private var chunkEnd = 0L
    private var chunkBytesRead = 0L
    private var finished = false

    override fun addTransferListener(transferListener: TransferListener) {
        inner.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chunk = dataSpec.customData as? ChunkInfo
        if (chunk == null || chunk.chunkSize <= 0L) {
            info = null
            return inner.open(dataSpec)
        }
        info = chunk
        baseSpec = dataSpec
        position = dataSpec.position
        endPosition =
            when {
                dataSpec.length != UNSET -> dataSpec.position + dataSpec.length
                chunk.contentLength > 0L -> chunk.contentLength
                else -> UNSET
            }
        finished = endPosition != UNSET && position >= endPosition
        if (!finished) openChunk()
        return if (endPosition == UNSET) UNSET else (endPosition - dataSpec.position).coerceAtLeast(0L)
    }

    private fun openChunk() {
        val chunk = requireNotNull(info)
        val base = requireNotNull(baseSpec)
        var length = chunk.chunkSize
        if (endPosition != UNSET) length = minOf(length, endPosition - position)
        val opened = inner.open(base.buildUpon().setPosition(position).setLength(length).build())
        chunkEnd = position + (if (opened != UNSET) opened else length)
        chunkBytesRead = 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (info == null) return inner.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (finished) return C.RESULT_END_OF_INPUT
            val read = inner.read(buffer, offset, length)
            if (read > 0) {
                position += read
                chunkBytesRead += read
                return read
            }
            if (read == 0) return 0
            // Chunk habis.
            if (chunkBytesRead == 0L || position < chunkEnd || (endPosition != UNSET && position >= endPosition)) {
                finished = true
                return C.RESULT_END_OF_INPUT
            }
            inner.close()
            try {
                openChunk()
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416) {
                    finished = true
                    return C.RESULT_END_OF_INPUT
                }
                throw e
            }
        }
    }

    override fun getUri(): Uri? = inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

    override fun close() {
        info = null
        baseSpec = null
        finished = false
        inner.close()
    }

    private companion object {
        val UNSET = C.LENGTH_UNSET.toLong()
    }
}
