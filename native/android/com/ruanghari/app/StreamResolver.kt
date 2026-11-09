package com.ruanghari.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.ruanghari.app.potoken.PoTokenGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlin.time.Clock

/**
 * Resolver ID video YouTube -> URL stream audio, memakai InnerTubeX.
 *
 * Logika diadaptasi dari Metrolist (app/.../utils/InnerTubeXPlayer.kt), GPL-3.0.
 * Diubah untuk Ruang Hari: tanpa modul :innertube Metrolist, tanpa Timber/DataStore,
 * transport Ktor/OkHttp dibuat di sini.
 */
object StreamResolver {
    private const val TAG = "RuangStream"
    @Volatile var stage: String = "idle"
        private set
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private const val FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_TTL_SECONDS = 5 * 60
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    private class Bundle(
        val httpClient: HttpClient,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    private class Failed(val clients: Set<String>, val atMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bundleMutex = Mutex()
    private val failures = ConcurrentHashMap<String, Failed>()

    @Volatile private var appContext: Context? = null
    @Volatile private var current: Bundle? = null

    /**
     * Resolve [videoId] di background. [onOk] / [onFail] dipanggil dari thread IO (bukan main thread).
     * Jika [rejectedClient] diisi, stream sebelumnya ditolak server (mis. 403): client itu
     * dikecualikan sebentar dan cipher/config di-refresh dulu.
     */
    fun resolve(
        context: Context,
        videoId: String,
        rejectedClient: String?,
        onOk: (ResolvedStream) -> Unit,
        onFail: (String) -> Unit,
    ): Job {
        appContext = context.applicationContext
        val completed = AtomicBoolean(false)
        val job = scope.launch {
            stage = "resolving"

            var stream: ResolvedStream? = null
            var message: String? = null
            try {
                if (rejectedClient != null) {
                    markFailed(videoId, rejectedClient)
                    try {
                        bundle().cipherService.refreshAfterStreamRejection()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "refreshAfterStreamRejection gagal: ${e::class.simpleName}")
                    }
                }
                stream = withTimeout(30_000L) { extract(videoId) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Timeout resolver untuk videoId=$videoId", e)
                message = "Resolver stream melewati batas 30 detik. Periksa jaringan atau coba lagu lain."
            } catch (e: CancellationException) {
                if (completed.compareAndSet(false, true)) onFail("Permintaan stream dibatalkan.")
                throw e
            } catch (e: StreamResolveException) {
                Log.w(TAG, "resolve gagal: reason=${e.reason}", e)
                message =
                    if (e.reason == StreamResolveException.Reason.NETWORK) {
                        "Koneksi stream gagal (${e.reason}): ${e.cause?.message ?: e.message}"
                    } else {
                        "Layanan menolak stream (${e.reason}). ${e.message ?: "Coba lagi."}"
                    }
            } catch (e: Exception) {
                Log.w(TAG, "resolve gagal", e)
                message = "Gagal menyiapkan stream: ${e.message ?: e::class.simpleName}"
            }
            val ok = stream
            if (completed.compareAndSet(false, true)) {
                if (ok != null) { stage = "resolved"; onOk(ok) }
                else { stage = "error"; onFail(message ?: "Gagal menyiapkan stream.") }
            }
        }
        // Independent deadline: blocking third-party IO must not strand the Capacitor callback.
        val deadline = watchdog.schedule({
            if (completed.compareAndSet(false, true)) {
                val atStage = stage
                stage = "error"
                onFail("Stream tidak tersedia dalam 30 detik (tahap: $atStage). Coba lagi; detail dicatat dengan tag RuangStream.")
                job.cancel()
            }
        }, 30, TimeUnit.SECONDS)
        job.invokeOnCompletion { deadline.cancel(false) }
        return job
    }

    private suspend fun extract(videoId: String): ResolvedStream {
        val context = requireNotNull(appContext) { "StreamResolver belum diinisialisasi" }
        val metered =
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
            }.getOrDefault(false)
        val quality = if (metered) AudioQuality.LOW else AudioQuality.AUTO
        val hints =
            ContentHints().withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = true,
            )
        stage = "extracting"
        val stream =
            requireNotNull(
                resolveWithClientFallback(failedClients(videoId)) { clients, useLowQuality ->
                    bundle().extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = clients,
                        audioQuality = if (useLowQuality) AudioQuality.LOW else quality,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    )
                },
            ) { "InnerTubeX tidak mengembalikan stream" }
        check(stream.sabrBootstrap == null) { "SABR tidak didukung mesin pemutar ini" }
        return stream.toResolved()
    }

    private suspend fun <T : Any> resolveWithClientFallback(
        excluded: Set<String>,
        extract: suspend (Set<String>, Boolean) -> T?,
    ): T? {
        if (excluded.isEmpty()) return extract(emptySet(), false)
        val alternative =
            try {
                extract(excluded, false)
            } catch (error: StreamResolveException) {
                if (error.reason != StreamResolveException.Reason.NO_PLAYABLE_STREAM) throw error
                null
            }
        if (alternative != null) return alternative
        Log.w(TAG, "Client alternatif habis; coba semua client dengan kualitas rendah")
        return extract(emptySet(), true)
    }

    private fun markFailed(videoId: String, clientName: String) {
        val now = System.currentTimeMillis()
        failures.compute(videoId) { _, old -> Failed(old?.clients.orEmpty() + clientName, now) }
    }

    private fun failedClients(videoId: String): Set<String> {
        val failed = failures[videoId] ?: return emptySet()
        if ((System.currentTimeMillis() - failed.atMs) !in 0 until FAILURE_TTL_MS) {
            failures.remove(videoId, failed)
            return emptySet()
        }
        return failed.clients
    }

    private suspend fun bundle(): Bundle {
        current?.let { return it }
        return bundleMutex.withLock {
            current ?: build().also { current = it }
        }
    }

    private fun build(): Bundle {
        val context = requireNotNull(appContext) { "StreamResolver belum diinisialisasi" }
        val httpClient = createHttpClient(context)
        val innerTube = InnerTube(httpClient)
        val remoteStore = RemotePlayerConfigStore(httpClient, configRepository(context), logger)
        val cipherService = YouTubeCipherService(httpClient, remoteStore, logger)
        val extractor =
            InnerTubeExtractor(
                configParser =
                    YtConfigParserImpl(httpClient, innerTube, remoteStore, logger)
                        .withEmbeddedConfigFallback(),
                cipherService = cipherService,
                innerTube = innerTube,
                tokenProvider = tokenProvider(context),
                logger = logger,
            )
        return Bundle(httpClient, cipherService, extractor)
    }

    // Sama seperti createClient() di innertube/InnerTube.kt milik Metrolist (tanpa proxy).
    private fun createHttpClient(context: Context) =
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 10_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }
            engine {
                config {
                    connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
                    connectTimeout(8, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.SECONDS)
                    writeTimeout(10, TimeUnit.SECONDS)
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                    callTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)
                    cache(okhttp3.Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
                }
            }
        }

    private fun tokenProvider(context: Context): TokenProvider {
        val generator = PoTokenGenerator(context)
        return object : TokenProvider {
            override val capabilities =
                TokenProviderCapabilities(
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    usesWebView = true,
                )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? =
                run {
                    stage = "token"
                    try {
                        withTimeout(8_000L) { generator.getWebClientPoToken(videoId, visitorData) }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "Token deadline reached; falling through to other clients")
                        null
                    } finally { stage = "extracting" }
                }?.let { token ->
                    PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData,
                    )
                }

            override suspend fun close() {
                generator.close()
            }
        }
    }

    private val logger =
        InnerTubeLogger { event ->
            val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Log.d(event.tag, message)
                InnerTubeLogLevel.INFO -> Log.i(event.tag, message)
                InnerTubeLogLevel.WARN -> Log.w(event.tag, message)
                InnerTubeLogLevel.ERROR -> Log.e(event.tag, message)
            }
        }

    private fun configRepository(context: Context): PlayerConfigRepository =
        object : PlayerConfigRepository {
            private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

            override val enabled: Boolean = true
            override val sourceUrl: String = PLAYER_CONFIG_URL
            override val defaultSourceUrl: String = PLAYER_CONFIG_URL
            override var cachedJson: String
                get() = preferences.getString("json", "").orEmpty()
                set(value) = preferences.edit().putString("json", value).apply()
            override var cachedAtMs: Long
                get() = preferences.getLong("cached_at_ms", 0L)
                set(value) = preferences.edit().putLong("cached_at_ms", value).apply()
            override var cachedSourceUrl: String
                get() = preferences.getString("source_url", "").orEmpty()
                set(value) = preferences.edit().putString("source_url", value).apply()
            override var cachedEtag: String
                get() = preferences.getString("etag", "").orEmpty()
                set(value) = preferences.edit().putString("etag", value).apply()
        }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private fun ExtractedStream.toResolved(): ResolvedStream {
        val metadata = mediaMetadata
        val ttlSeconds =
            expiresAt
                ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                ?.coerceAtLeast(1)
                ?: DEFAULT_TTL_SECONDS
        return ResolvedStream(
            url = audioUrl,
            headers = headers,
            clientName = clientName,
            mimeType = mimeType.orEmpty(),
            bitrate = (bitrate ?: 0).toLong(),
            contentLength = contentLengthBytes?.toLong() ?: -1L,
            expiresAtMs = System.currentTimeMillis() + ttlSeconds * 1000L,
            requireBoundedRange = requireBoundedRange,
            rangeChunkSizeBytes = rangeChunkSizeBytes,
            useRangeChunks = useRangeChunks,
            title = metadata?.title,
            author = metadata?.author,
            durationMs = (metadata?.durationSeconds?.toLong() ?: 0L) * 1000L,
        )
    }
}
