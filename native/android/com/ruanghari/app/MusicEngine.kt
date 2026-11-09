package com.ruanghari.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import android.util.Log

/**
 * Mesin pemutar native (Media3 ExoPlayer) untuk Ruang Hari.
 *
 * Alur: ID lagu -> StreamResolver (InnerTubeX) -> ExoPlayer. Header stream dan Range chunk
 * dipasang lewat ResolvingDataSource + ChunkedDataSource, mengikuti pola MusicService Metrolist.
 * Semua akses ExoPlayer dilakukan di main thread.
 */
@UnstableApi
class MusicEngine(context: Context) {
    interface StartCallback {
        fun onStarted(clientName: String, mimeType: String, bitrate: Long)
        fun onError(message: String)
    }

    interface StatusCallback {
        fun onStatus(
            playing: Boolean,
            positionMs: Long,
            durationMs: Long,
            state: String,
            videoId: String?,
            error: String?,
        )
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val streams = ConcurrentHashMap<String, ResolvedStream>()
    private val requestSeq = AtomicLong(0)

    // Hanya disentuh dari main thread.
    private var player: ExoPlayer? = null
    private var currentVideoId: String? = null
    private var lastError: String? = null
    private var retriesLeft = 0
    private var resolveJob: Job? = null
    private var phase = "idle"
    private var bufferDeadline: Runnable? = null
    private var released = false
    private fun clearDeadline() { bufferDeadline?.let { main.removeCallbacks(it) }; bufferDeadline = null }
    private fun armDeadline() {
        clearDeadline()
        val seq = requestSeq.get()
        val timeout = Runnable {
            if (!released && seq == requestSeq.get() && player?.playbackState == Player.STATE_BUFFERING) {
                lastError = "Server audio tidak mengirim data dalam 20 detik. Coba lagi."
                phase = "error"
                player?.stop()
            }
        }
        bufferDeadline = timeout
        main.postDelayed(timeout, 20_000L)
    }

    fun playVideo(videoId: String, callback: StartCallback) {
        val seq = requestSeq.incrementAndGet()
        resolveJob?.cancel()
        main.post {
            if (released || seq != requestSeq.get()) return@post
            clearDeadline()
            player?.stop()
            phase = "resolving"
            currentVideoId = videoId
            lastError = null
            retriesLeft = 1
        }
        resolveAndStart(videoId, seq, 0L, null, callback)
    }

    fun playUrl(url: String) {
        requestSeq.incrementAndGet()
        resolveJob?.cancel()
        main.post {
            clearDeadline()
            currentVideoId = null
            lastError = null
            val p = ensurePlayer()
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.play()
        }
    }

    fun pause() { main.post { player?.pause() } }

    fun resume() { main.post { player?.play() } }

    fun stop() {
        requestSeq.incrementAndGet()
        resolveJob?.cancel()
        main.post {
            clearDeadline()
            player?.stop()
            player?.clearMediaItems()
            currentVideoId = null
            lastError = null
            phase = "idle"
        }
    }

    fun seek(positionMs: Long) { main.post { player?.seekTo(positionMs) } }

    fun status(callback: StatusCallback) {
        main.post {
            val p = player
            val id = currentVideoId
            var duration = p?.duration ?: 0L
            if (duration == C.TIME_UNSET || duration < 0L) duration = id?.let { streams[it]?.durationMs } ?: 0L
            val state =
                when {
                    lastError != null -> "error"
                    phase == "resolving" -> StreamResolver.stage
                    p?.playbackState == Player.STATE_BUFFERING -> "buffering"
                    p?.playbackState == Player.STATE_READY -> "ready"
                    p?.playbackState == Player.STATE_ENDED -> "ended"
                    else -> "idle"
                }
            callback.onStatus(
                p?.isPlaying ?: false,
                p?.currentPosition ?: 0L,
                duration,
                state,
                id,
                lastError,
            )
        }
    }

    fun release() {
        requestSeq.incrementAndGet()
        resolveJob?.cancel()
        main.post {
            clearDeadline()
            released = true
            player?.release()
            player = null
            currentVideoId = null
        }
    }

    private fun resolveAndStart(
        videoId: String,
        seq: Long,
        resumeMs: Long,
        rejectedClient: String?,
        callback: StartCallback?,
    ) {
        resolveJob = StreamResolver.resolve(
            appContext,
            videoId,
            rejectedClient,
            onOk = { stream ->
                main.post {
                    if (seq != requestSeq.get()) {
                        callback?.onError("Diganti lagu lain.")
                    } else {
                        try {
                            phase = "buffering"
                            streams[videoId] = stream
                            start(videoId, stream, resumeMs)
                        } catch (error: Exception) {
                            lastError = "Gagal membuka audio: ${error.message}"
                            phase = "error"
                            callback?.onError(lastError!!)
                            return@post
                        }
                        callback?.onStarted(stream.clientName, stream.mimeType, stream.bitrate)
                    }
                }
            },
            onFail = { message ->
                main.post {
                    if (seq != requestSeq.get()) {
                        callback?.onError("Diganti lagu lain.")
                    } else {
                        lastError = message
                        phase = "error"
                        callback?.onError(message)
                    }
                }
            },
        )
    }

    private fun start(videoId: String, stream: ResolvedStream, resumeMs: Long) {
        val p = ensurePlayer()
        val item =
            MediaItem.Builder()
                .setUri(stream.url)
                .setMediaId(videoId)
                .setCustomCacheKey(videoId) // jadi DataSpec.key, dipakai resolver di bawah
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(stream.title).setArtist(stream.author).build(),
                ).build()
        p.setMediaItem(item, resumeMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http =
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
        val chunked = DataSource.Factory { ChunkedDataSource(http) }
        val resolving = ResolvingDataSource.Factory(chunked, ResolvingDataSource.Resolver { spec -> applyStream(spec) })
        val extractors = ExtractorsFactory { arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor()) }
        val built =
            ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(resolving, extractors))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        built.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) armDeadline()
                    else clearDeadline()
                }
                override fun onPlayerError(error: PlaybackException) {
                    handleError(error)
                }
            },
        )
        player = built
        return built
    }

    /** Dipanggil di thread loader ExoPlayer: pasang header stream dan info chunk. */
    private fun applyStream(spec: DataSpec): DataSpec {
        val key = spec.key ?: return spec
        val stream = streams[key] ?: return spec
        val headers = HashMap(spec.httpRequestHeaders)
        headers.putAll(stream.headers)
        val chunk =
            if ((stream.requireBoundedRange || stream.useRangeChunks) && stream.rangeChunkSizeBytes > 0L) {
                ChunkedDataSource.ChunkInfo(stream.rangeChunkSizeBytes, stream.contentLength)
            } else {
                null
            }
        return spec.buildUpon().setHttpRequestHeaders(headers).setCustomData(chunk).build()
    }

    private fun handleError(error: PlaybackException) {
        Log.e("RuangStream", "Player error: ${error.errorCodeName}", error)
        clearDeadline()
        val id = currentVideoId ?: return
        val stream = streams[id]
        if (stream != null && retriesLeft > 0 && isStreamRejected(error)) {
            retriesLeft--
            val position = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
            phase = "resolving"
            resolveAndStart(id, requestSeq.get(), position, stream.clientName, null)
            return
        }
        phase = "error"
        lastError = "Pemutaran gagal (${error.errorCodeName}): ${error.cause?.message ?: error.message}"
        player?.stop()
    }

    private fun isStreamRejected(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException &&
                cause.responseCode in intArrayOf(401, 403, 404, 410)
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
