package com.walkietalkie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.walkietalkie.MainActivity
import com.walkietalkie.R
import com.walkietalkie.audio.AudioStreamer
import com.walkietalkie.network.UdpMulticastManager

/**
 * Foreground service that owns network and audio resources so the app stays
 * alive after the activity is backgrounded or destroyed.
 *
 * Responsibilities:
 *  - Runs UdpMulticastManager and AudioStreamer for the lifetime of the service
 *  - Shows / hides a system overlay badge when transmitting in the background
 *  - Plays beep tones at PTT start and stop
 *  - Updates the persistent foreground notification with current status
 */
class WalkieTalkieService : Service() {

    companion object {
        private const val CHANNEL_ID = "wt_foreground"
        private const val NOTIF_ID   = 1
    }

    inner class ServiceBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    private val binder  = ServiceBinder()
    private val handler = Handler(Looper.getMainLooper())

    lateinit var udpManager: UdpMulticastManager
        private set
    lateinit var audioStreamer: AudioStreamer
        private set

    private var wm: WindowManager? = null
    private var overlayView: View? = null

    private val beepAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile var isTransmitting   = false; private set
    @Volatile var isNetworkStarted = false; private set

    /**
     * Set to true by MainActivity while it is in the foreground (onResume →
     * onPause). The overlay is only shown when this is false (app in background).
     */
    @Volatile var isActivityVisible = false

    /**
     * Called on the main thread for every PING / AUDIO / LEAVE packet after the
     * service has filtered for group membership and rate limits.
     * Audio playback is handled internally; only presence events reach this callback.
     */
    var onUserEvent: ((UdpMulticastManager.Packet) -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        udpManager   = UdpMulticastManager(this)
        audioStreamer = AudioStreamer(udpManager)
        wm            = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Service owns the packet callback; it plays audio and notifies the
        // activity (if bound) for user-list updates.
        udpManager.onPacketReceived = { pkt ->
            if (pkt.type == UdpMulticastManager.TYPE_AUDIO && pkt.username != udpManager.username) {
                audioStreamer.playAudio(pkt.data)
            }
            handler.post { onUserEvent?.invoke(pkt) }
        }

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Ready"))
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        if (isTransmitting) {
            isTransmitting = false
            audioStreamer.stopTransmitting()
        }
        if (isNetworkStarted) {
            isNetworkStarted = false
            udpManager.stop()
        }
        audioStreamer.release()
    }

    // ── Network ───────────────────────────────────────────────────────────────

    fun startNetwork() {
        if (isNetworkStarted) return
        isNetworkStarted = true
        audioStreamer.initPlayback()
        Thread { udpManager.start() }.start()
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    fun startTransmitting() {
        if (isTransmitting) return
        isTransmitting = true
        playBeep()
        audioStreamer.startTransmitting()
        updateNotification("Transmitting...")
        if (!isActivityVisible) showOverlay()
    }

    fun stopTransmitting() {
        if (!isTransmitting) return
        isTransmitting = false
        audioStreamer.stopTransmitting()
        playBeep()
        updateNotification("Ready")
        hideOverlay()
    }

    // ── Beep ──────────────────────────────────────────────────────────────────

    private fun playBeep() {
        try {
            MediaPlayer.create(this, R.raw.beep, beepAttrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?.apply { setOnCompletionListener { it.release() }; start() }
        } catch (_: Exception) {}
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val tv = TextView(this).apply {
            text = "Transmitting"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC880000"))
            setPadding(48, 24, 48, 24)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        try {
            wm?.addView(tv, params)
            overlayView = tv
        } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Walkie Talkie", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps radio active in background" }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Walkie Talkie")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
    }
}
