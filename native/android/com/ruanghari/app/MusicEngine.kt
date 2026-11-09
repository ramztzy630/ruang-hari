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
import android.net.Uri
import com.getcapacitor.JSObject
import org.json.JSONArray

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
    private val displayMetadata = ConcurrentHashMap<String, MediaMetadata>()
    private data class QueueTrack(val id: String, val title: String, val artist: String, val album: String, val cover: String)
    @Volatile private var shuffleQueue: List<QueueTrack> = emptyList()
    @Volatile private var queueMode = "shuffle"
    private var autoplayPending = false

    // --- Lagu berikutnya otomatis (gaya Spotify) ---
    // Lagu berikutnya di-resolve dan dimasukkan ke playlist ExoPlayer SEBELUM lagu aktif habis. Transisi terjadi di dalam
    // ExoPlayer, jadi pemutaran tidak pernah berhenti dan service tetap foreground (jaringan tidak diblokir saat layar mati).
    // Hanya disentuh dari main thread.
    private var pendingNext: String? = null   // sudah di-resolve, menunggu dimasukkan ke ExoPlayer
    private var nextVideoId: String? = null   // sudah ada di playlist ExoPlayer setelah lagu aktif
    private var prefetching: String? = null
    private var prefetchJob: Job? = null
    private var prefetchFailures = 0
    private var prefetchRetryAt = 0L
    private val recentIds = ArrayDeque<String>()
    private val failedNext = HashSet<String>()
    private var tickerRunning = false
    private val ticker = Runnable {
        tickerRunning = false
        tick()
    }
    fun setQueue(items: JSONArray) {
        shuffleQueue = (0 until minOf(items.length(), 40)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@mapNotNull null
            QueueTrack(id, item.optString("title"), item.optString("artistName"), item.optString("album"), item.optString("cover"))
        }.distinctBy { it.id }
    }
    fun setQueueMode(mode: String) {
        queueMode = if (mode == "sequential") "sequential" else "shuffle"
    }

    private fun rememberRecent(id: String) {
        recentIds.remove(id)
        recentIds.addLast(id)
        val keep = maxOf(1, minOf(shuffleQueue.size - 2, 10))
        while (recentIds.size > keep) recentIds.removeFirst()
    }

    /** Pilih lagu berikutnya: berurutan (playlist) atau acak tanpa mengulang yang baru diputar (hasil pencarian). */
    private fun pickNext(): QueueTrack? {
        val queue = shuffleQueue
        if (queue.size < 2) return null
        val current = currentVideoId.orEmpty()
        if (queueMode == "sequential") {
            val currentIndex = queue.indexOfFirst { it.id == current }
            for (step in 1..queue.size) {
                val candidate = queue[(currentIndex + step).mod(queue.size)]
                if (candidate.id != current && candidate.id !in failedNext) return candidate
            }
            return null
        }
        var candidates = queue.filter { it.id != current && it.id !in recentIds && it.id !in failedNext }
        if (candidates.isEmpty()) candidates = queue.filter { it.id != current && it.id !in failedNext }
        if (candidates.isEmpty()) return null
        val index = ShufflePolicy.pickIndex(candidates.map { it.id }, current)
        return if (index < 0) null else candidates[index]
    }

    private fun dropNext() {
        pendingNext = null
        nextVideoId = null
        prefetching = null
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchFailures = 0
        prefetchRetryAt = 0L
        failedNext.clear()
    }

    private fun startTicker() {
        if (tickerRunning || released) return
        tickerRunning = true
        main.postDelayed(ticker, 2000L)
    }

    private fun stopTicker() {
        tickerRunning = false
        main.removeCallbacks(ticker)
    }

    private fun tick() {
        if (released) return
        val p = player ?: return
        if (currentVideoId == null) return
        maybePrefetch(p)
        maybeAppendNext(p)
        startTicker()
    }

    private fun maybePrefetch(p: ExoPlayer) {
        if (nextVideoId != null || pendingNext != null || prefetching != null) return
        if (!p.isPlaying || p.currentPosition < 4000L) return
        if (System.currentTimeMillis() < prefetchRetryAt || prefetchFailures >= 4) return
        val next = pickNext() ?: return
        val seq = requestSeq.get()
        prefetching = next.id
        setDisplayMetadata(next.id, next.title, next.artist, next.album, next.cover)
        prefetchJob = StreamResolver.resolve(
            appContext,
            next.id,
            null,
            onOk = { stream ->
                main.post {
                    if (released || seq != requestSeq.get() || prefetching != next.id) return@post
                    prefetching = null
                    streams[next.id] = stream
                    pendingNext = next.id
                }
            },
            onFail = { message ->
                main.post {
                    if (released || seq != requestSeq.get() || prefetching != next.id) return@post
                    prefetching = null
                    prefetchFailures++
                    prefetchRetryAt = System.currentTimeMillis() + 5000L * prefetchFailures
                    if (!(message.contains("NETWORK") || message.contains("Koneksi"))) failedNext.add(next.id)
                    Log.w("RuangStream", "Prefetch lagu berikutnya gagal: $message")
                }
            },
        )
    }

    /** Masukkan lagu berikutnya ke playlist ExoPlayer saat lagu aktif tinggal sebentar lagi. */
    private fun maybeAppendNext(p: ExoPlayer) {
        val id = pendingNext ?: return
        if (nextVideoId != null) return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        if (duration - p.currentPosition > 35_000L) return
        val stream = streams[id]
        if (stream == null) {
            pendingNext = null
            return
        }
        while (p.mediaItemCount > p.currentMediaItemIndex + 1) p.removeMediaItem(p.mediaItemCount - 1)
        p.addMediaItem(buildItem(id, stream))
        nextVideoId = id
        pendingNext = null
    }

    /** Cadangan bila lagu berikutnya belum sempat diantre: lanjutkan setelah STATE_ENDED. */
    private fun advanceAfterEnd() {
        if (autoplayPending || released) return
        val pendingId = pendingNext
        val pendingStream = pendingId?.let { streams[it] }
        if (pendingId != null && pendingStream != null) {
            currentVideoId?.let { rememberRecent(it) }
            currentVideoId = pendingId
            pendingNext = null
            nextVideoId = null
            prefetching = null
            prefetchFailures = 0
            phase = "buffering"
            lastError = null
            retriesLeft = 1
            start(pendingId, pendingStream, 0L)
            return
        }
        val next = pickNext() ?: shuffleQueue.singleOrNull() ?: return
        startAutoplay(next, 0)
    }

    private fun startAutoplay(next: QueueTrack, attempt: Int) {
        autoplayPending = true
        setDisplayMetadata(next.id, next.title, next.artist, next.album, next.cover)
        playVideo(next.id, object : StartCallback {
            override fun onStarted(clientName: String, mimeType: String, bitrate: Long) { autoplayPending = false }
            override fun onError(message: String) {
                autoplayPending = false
                val network = message.contains("NETWORK") || message.contains("Koneksi")
                if (!network || attempt >= 3 || released) return
                val seq = requestSeq.get()
                main.postDelayed({
                    if (!released && seq == requestSeq.get()) startAutoplay(next, attempt + 1)
                }, 4000L * (attempt + 1))
            }
        })
    }

    fun setDisplayMetadata(id: String, title: String, artist: String, album: String, cover: String) {
        val builder = MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        if (cover.startsWith("https://")) builder.setArtworkUri(Uri.parse(cover))
        displayMetadata[id] = builder.build()
        if (displayMetadata.size > 16) {
            val keep = setOf(id, currentVideoId, nextVideoId, pendingNext)
            displayMetadata.keys.filter { it !in keep }.forEach { displayMetadata.remove(it) }
        }
    }
    // Called on the main thread by status()'s callback.
    fun metadataSnapshot(): JSObject {
        val item = player?.currentMediaItem
        val info = currentVideoId?.let { displayMetadata[it] } ?: item?.takeIf { it.mediaId == currentVideoId }?.mediaMetadata
        return JSObject().apply {
            put("title", info?.title?.toString().orEmpty())
            put("artistName", info?.artist?.toString().orEmpty())
            put("album", info?.albumTitle?.toString().orEmpty())
            put("cover", info?.artworkUri?.toString().orEmpty())
        }
    }
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
            dropNext()
            currentVideoId?.takeIf { it != videoId }?.let { rememberRecent(it) }
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
            dropNext()
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
            autoplayPending = false
            shuffleQueue = emptyList()
            dropNext()
            stopTicker()
            recentIds.clear()
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
            stopTicker()
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

    private fun buildItem(videoId: String, stream: ResolvedStream): MediaItem =
        MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(videoId)
            .setCustomCacheKey(videoId) // jadi DataSpec.key, dipakai resolver di bawah
            .setMediaMetadata(
                displayMetadata[videoId] ?: MediaMetadata.Builder().setTitle(stream.title).setArtist(stream.author).build(),
            ).build()

    private fun start(videoId: String, stream: ResolvedStream, resumeMs: Long) {
        val p = ensurePlayer()
        p.setMediaItem(buildItem(videoId, stream), resumeMs)
        p.prepare()
        p.playWhenReady = true
        startTicker()
    }

    fun sessionPlayer(): ExoPlayer = ensurePlayer()

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
                    if (playbackState == Player.STATE_ENDED && built.playWhenReady) advanceAfterEnd()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                    val id = mediaItem?.mediaId ?: return
                    if (id != nextVideoId) return
                    currentVideoId?.let { rememberRecent(it) }
                    currentVideoId = id
                    nextVideoId = null
                    pendingNext = null
                    prefetching = null
                    prefetchFailures = 0
                    prefetchRetryAt = 0L
                    phase = "buffering"
                    lastError = null
                    retriesLeft = 1
                    main.post {
                        val current = player ?: return@post
                        if (current.currentMediaItemIndex > 0) current.removeMediaItems(0, current.currentMediaItemIndex)
                    }
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
        val queued = nextVideoId
        if (queued != null) {
            // Kemungkinan besar error datang dari lagu berikutnya yang sedang disiapkan: jangan matikan lagu aktif.
            nextVideoId = null
            pendingNext = null
            streams.remove(queued)
            failedNext.add(queued)
            val active = currentVideoId
            val activeStream = active?.let { streams[it] }
            val p = player
            if (active != null && activeStream != null && p != null && p.currentMediaItem?.mediaId == active) {
                try {
                    start(active, activeStream, p.currentPosition.coerceAtLeast(0L))
                    return
                } catch (e: Exception) {
                    Log.w("RuangStream", "Gagal memulihkan lagu aktif: ${e.message}")
                }
            }
        }
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
