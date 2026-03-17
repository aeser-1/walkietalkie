package com.walkietalkie

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.walkietalkie.network.UdpMulticastManager
import com.walkietalkie.service.WalkieTalkieService

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_NAME        = "wt_prefs"
        const val PREF_USERNAME    = "username"
        const val PREF_GROUP       = "group"
        const val PREF_PTT_TYPE    = "ptt_type"

        const val PTT_SCREEN       = 0
        const val PTT_VOLUME_UP    = 1
        const val PTT_VOLUME_DOWN  = 2

        private const val REQ_AUDIO_PERMISSION = 1001
        private const val REQ_NOTIF_PERMISSION = 1002
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var tvUsername: TextView
    private lateinit var tvGroup: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvUsers: TextView
    private lateinit var btnPtt: Button
    private lateinit var btnSettings: Button

    // username -> last-seen timestamp (ms). Capped to prevent memory exhaustion.
    private val activeUsers    = LinkedHashMap<String, Long>()
    private val MAX_ACTIVE_USERS = 50
    private val handler        = Handler(Looper.getMainLooper())

    @Volatile private var transmitting = false

    // ── Service binding ───────────────────────────────────────────────────────

    private var service: WalkieTalkieService? = null
    private var isBound = false
    private var audioPermissionGranted = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as WalkieTalkieService.ServiceBinder).getService()
            isBound = true
            service!!.isActivityVisible = true
            service!!.onUserEvent = ::handleUserEvent

            if (audioPermissionGranted && !service!!.isNetworkStarted) {
                service!!.startNetwork()
            }

            // Sync transmitting state in case service was already transmitting
            if (service!!.isTransmitting && !transmitting) {
                transmitting = true
                updatePttUi(transmitting = true)
            }

            applySettings()
            handler.post(cleanupTask)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            service = null
        }
    }

    // Periodic cleanup: remove users not heard from in 5 s
    private val cleanupTask = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 5000
            val stale  = activeUsers.entries.filter { it.value < cutoff }.map { it.key }
            if (stale.isNotEmpty()) {
                stale.forEach { activeUsers.remove(it) }
                refreshUserList()
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        tvUsername  = findViewById(R.id.tv_username)
        tvGroup     = findViewById(R.id.tv_group)
        tvStatus    = findViewById(R.id.tv_status)
        tvUsers     = findViewById(R.id.tv_users)
        btnPtt      = findViewById(R.id.btn_ptt)
        btnSettings = findViewById(R.id.btn_settings)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        checkPermissionAndStart()
        requestOverlayPermission()
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        // startService ensures the service outlives this activity binding
        val intent = Intent(this, WalkieTalkieService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        service?.isActivityVisible = true
        applySettings()
        setupPttButton()
    }

    override fun onPause() {
        super.onPause()
        service?.isActivityVisible = false
        if (transmitting) stopTransmitting()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(cleanupTask)
        if (isBound) {
            service?.onUserEvent = null
            unbindService(serviceConnection)
            isBound  = false
            service  = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service keeps running for background audio receive; we only unbind here.
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun applySettings() {
        val username = prefs.getString(PREF_USERNAME, "").orEmpty()
        val group    = prefs.getString(PREF_GROUP, "").orEmpty()

        if (username.isEmpty() || group.isEmpty()) {
            tvStatus.text = "Tap Settings to configure"
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        tvUsername.text = "User: $username"
        tvGroup.text    = "Group: $group"

        service?.let {
            it.udpManager.username = username
            it.udpManager.group    = group
        }
        refreshUserList()
    }

    // ── PTT button wiring ─────────────────────────────────────────────────────

    private fun setupPttButton() {
        val pttType = prefs.getInt(PREF_PTT_TYPE, PTT_SCREEN)

        if (pttType == PTT_SCREEN) {
            btnPtt.visibility = View.VISIBLE
            btnPtt.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN                          -> startTransmitting()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTransmitting()
                }
                true
            }
        } else {
            btnPtt.visibility = View.GONE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return super.onKeyDown(keyCode, event)
        val pttType = prefs.getInt(PREF_PTT_TYPE, PTT_SCREEN)
        if (matchesPtt(pttType, keyCode)) {
            if (!transmitting) startTransmitting()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val pttType = prefs.getInt(PREF_PTT_TYPE, PTT_SCREEN)
        if (matchesPtt(pttType, keyCode)) {
            stopTransmitting()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun matchesPtt(pttType: Int, keyCode: Int): Boolean =
        (pttType == PTT_VOLUME_UP   && keyCode == KeyEvent.KEYCODE_VOLUME_UP) ||
        (pttType == PTT_VOLUME_DOWN && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)

    // ── Transmit control ──────────────────────────────────────────────────────

    private fun startTransmitting() {
        if (transmitting) return
        transmitting = true
        updatePttUi(transmitting = true)
        service?.startTransmitting()
    }

    private fun stopTransmitting() {
        if (!transmitting) return
        transmitting = false
        updatePttUi(transmitting = false)
        service?.stopTransmitting()
    }

    private fun updatePttUi(transmitting: Boolean) {
        if (transmitting) {
            tvStatus.text = "Transmitting..."
            btnPtt.text   = "RELEASE TO STOP"
            btnPtt.setBackgroundColor(getColor(R.color.ptt_active))
        } else {
            tvStatus.text = "Ready"
            btnPtt.text   = "PRESS TO TALK"
            btnPtt.setBackgroundColor(getColor(R.color.ptt_idle))
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION
            )
        } else {
            audioPermissionGranted = true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Grant 'Display over other apps' for the transmit indicator",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                audioPermissionGranted = true
                if (isBound && !service!!.isNetworkStarted) {
                    service!!.startNetwork()
                }
            } else {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ── Packet handling ───────────────────────────────────────────────────────

    // Called on the main thread by WalkieTalkieService for presence events.
    // Audio playback is handled by the service itself.
    private fun handleUserEvent(pkt: UdpMulticastManager.Packet) {
        when (pkt.type) {
            UdpMulticastManager.TYPE_PING,
            UdpMulticastManager.TYPE_AUDIO -> {
                if (!activeUsers.containsKey(pkt.username) && activeUsers.size >= MAX_ACTIVE_USERS) {
                    val oldest = activeUsers.minByOrNull { it.value }?.key
                    if (oldest != null) activeUsers.remove(oldest)
                }
                activeUsers[pkt.username] = System.currentTimeMillis()
                refreshUserList()
            }
            UdpMulticastManager.TYPE_LEAVE -> {
                activeUsers.remove(pkt.username)
                refreshUserList()
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshUserList() {
        val myName = prefs.getString(PREF_USERNAME, "").orEmpty()
        val lines  = mutableListOf<String>()
        if (myName.isNotEmpty()) lines.add("$myName  (you)")
        activeUsers.keys.filter { it != myName }.forEach { lines.add(it) }
        tvUsers.text = lines.joinToString("\n") { "• $it" }
    }
}
