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
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
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
 * When the activity is not visible, shows a floating "HOLD TO TALK" overlay
 * button so the user can transmit from the home screen or any other app.
 * A PARTIAL_WAKE_LOCK keeps the CPU running for reliable packet reception
 * while the screen is off.
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
    private var pttOverlayView: TextView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val beepAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile var isTransmitting   = false; private set
    @Volatile var isNetworkStarted = false; private set
    @Volatile var isActivityVisible = false; private set

    /**
     * Called on the main thread for every PING / AUDIO / LEAVE packet.
     * Audio playback is handled internally; only presence events reach here.
     */
    var onUserEvent: ((UdpMulticastManager.Packet) -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        udpManager   = UdpMulticastManager(this)
        audioStreamer = AudioStreamer(udpManager)
        wm            = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
        hidePttOverlay()
        if (isTransmitting) {
            isTransmitting = false
            audioStreamer.stopTransmitting()
        }
        if (isNetworkStarted) {
            isNetworkStarted = false
            udpManager.stop()
        }
        audioStreamer.release()
        releaseWakeLock()
    }

    // ── Activity visibility ───────────────────────────────────────────────────

    /** Called by MainActivity in onResume / onServiceConnected. */
    fun onActivityVisible() {
        isActivityVisible = true
        hidePttOverlay()
    }

    /** Called by MainActivity in onPause. Shows the floating PTT button. */
    fun onActivityHidden() {
        isActivityVisible = false
        if (isNetworkStarted) showPttOverlay()
    }

    // ── Network ───────────────────────────────────────────────────────────────

    fun startNetwork() {
        if (isNetworkStarted) return
        isNetworkStarted = true
        acquireWakeLock()
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
        updatePttOverlayAppearance(transmitting = true)
    }

    fun stopTransmitting() {
        if (!isTransmitting) return
        isTransmitting = false
        audioStreamer.stopTransmitting()
        playBeep()
        updateNotification("Ready")
        updatePttOverlayAppearance(transmitting = false)
    }

    // ── Beep ──────────────────────────────────────────────────────────────────

    private fun playBeep() {
        try {
            MediaPlayer.create(this, R.raw.beep, beepAttrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?.apply { setOnCompletionListener { it.release() }; start() }
        } catch (_: Exception) {}
    }

    // ── Floating PTT overlay ──────────────────────────────────────────────────

    private fun showPttOverlay() {
        if (pttOverlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val btn = TextView(this).apply {
            isClickable = true
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(56, 28, 56, 28)
            gravity = Gravity.CENTER
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startTransmitting()
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        stopTransmitting()
                    }
                }
                true
            }
        }
        applyPttOverlayStyle(btn, transmitting = false)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        try {
            wm?.addView(btn, params)
            pttOverlayView = btn
        } catch (_: Exception) {}
    }

    private fun hidePttOverlay() {
        pttOverlayView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            pttOverlayView = null
        }
    }

    private fun updatePttOverlayAppearance(transmitting: Boolean) {
        pttOverlayView?.let { handler.post { applyPttOverlayStyle(it, transmitting) } }
    }

    private fun applyPttOverlayStyle(view: TextView, transmitting: Boolean) {
        if (transmitting) {
            view.text = "TRANSMITTING"
            view.setBackgroundColor(Color.parseColor("#FF6F00"))
        } else {
            view.text = "HOLD TO TALK"
            view.setBackgroundColor(Color.parseColor("#C62828"))
        }
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalkieTalkie::NetworkLock")
            .also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
