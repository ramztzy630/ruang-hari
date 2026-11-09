package com.ruanghari.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Owns playback independently of the Capacitor Activity/WebView lifecycle. */
@UnstableApi
class RuangMusicService : MediaSessionService() {
    companion object { const val LOCAL_BIND = "com.ruanghari.app.BIND_MUSIC" }
    private lateinit var engine: MusicEngine
    private var session: MediaSession? = null
    inner class LocalBinder : Binder() { fun getEngine(): MusicEngine = engine }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        engine = MusicEngine(this)
        val builder = MediaSession.Builder(this, engine.sessionPlayer())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build().also { addSession(it) }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == LOCAL_BIND) binder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        engine.release()
        super.onDestroy()
    }
    // MediaSessionService keeps ongoing playback when the Activity/task is removed.
}
