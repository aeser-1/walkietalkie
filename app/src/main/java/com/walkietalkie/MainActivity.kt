package com.walkietalkie

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.walkietalkie.audio.AudioStreamer
import com.walkietalkie.network.UdpMulticastManager

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
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var udpManager: UdpMulticastManager
    private lateinit var audioStreamer: AudioStreamer

    private lateinit var tvUsername: TextView
    private lateinit var tvGroup: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvUsers: TextView
    private lateinit var btnPtt: Button
    private lateinit var btnSettings: Button

    // username -> last-seen timestamp (ms). Capped to prevent memory exhaustion.
    private val activeUsers = LinkedHashMap<String, Long>()
    private val MAX_ACTIVE_USERS = 50
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var transmitting = false
    private var networkStarted = false

    // Periodic cleanup: remove users not heard from in 5 s
    private val cleanupTask = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 5000
            val stale = activeUsers.entries.filter { it.value < cutoff }.map { it.key }
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

        tvUsername = findViewById(R.id.tv_username)
        tvGroup    = findViewById(R.id.tv_group)
        tvStatus   = findViewById(R.id.tv_status)
        tvUsers    = findViewById(R.id.tv_users)
        btnPtt     = findViewById(R.id.btn_ptt)
        btnSettings = findViewById(R.id.btn_settings)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        udpManager   = UdpMulticastManager(this)
        audioStreamer = AudioStreamer(udpManager)

        checkPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        setupPttButton()
    }

    override fun onPause() {
        super.onPause()
        // Always stop transmitting when leaving foreground
        if (transmitting) stopTransmitting()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(cleanupTask)
        Thread {
            udpManager.stop()
            audioStreamer.release()
        }.start()
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

        udpManager.username = username
        udpManager.group    = group
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
            // Handled in onKeyDown/onKeyUp
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

    private fun matchesPtt(pttType: Int, keyCode: Int): Boolean {
        return (pttType == PTT_VOLUME_UP   && keyCode == KeyEvent.KEYCODE_VOLUME_UP) ||
               (pttType == PTT_VOLUME_DOWN && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
    }

    // ── Transmit control ──────────────────────────────────────────────────────

    private fun startTransmitting() {
        if (transmitting) return
        transmitting = true
        tvStatus.text = "Transmitting..."
        btnPtt.text   = "RELEASE TO STOP"
        btnPtt.setBackgroundColor(getColor(R.color.ptt_active))
        audioStreamer.startTransmitting()
    }

    private fun stopTransmitting() {
        if (!transmitting) return
        transmitting = false
        tvStatus.text = "Ready"
        btnPtt.text   = "PRESS TO TALK"
        btnPtt.setBackgroundColor(getColor(R.color.ptt_idle))
        audioStreamer.stopTransmitting()
    }

    // ── Permissions & networking ───────────────────────────────────────────────

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION
            )
        } else {
            startNetworking()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startNetworking()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startNetworking() {
        if (networkStarted) return
        networkStarted = true

        udpManager.onPacketReceived = { pkt ->
            val myName = prefs.getString(PREF_USERNAME, "").orEmpty()
            when (pkt.type) {
                UdpMulticastManager.TYPE_PING,
                UdpMulticastManager.TYPE_AUDIO -> {
                    // Enforce cap — evict oldest before inserting a new unknown username
                    if (!activeUsers.containsKey(pkt.username) && activeUsers.size >= MAX_ACTIVE_USERS) {
                        val oldest = activeUsers.minByOrNull { it.value }?.key
                        if (oldest != null) activeUsers.remove(oldest)
                    }
                    activeUsers[pkt.username] = System.currentTimeMillis()
                    handler.post { refreshUserList() }
                }
                UdpMulticastManager.TYPE_LEAVE -> {
                    activeUsers.remove(pkt.username)
                    handler.post { refreshUserList() }
                }
            }
            // Play audio from others only
            if (pkt.type == UdpMulticastManager.TYPE_AUDIO && pkt.username != myName) {
                audioStreamer.playAudio(pkt.data)
            }
        }

        audioStreamer.initPlayback()

        Thread {
            udpManager.start()
            handler.post { tvStatus.text = "Ready" }
        }.start()

        handler.post(cleanupTask)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshUserList() {
        val myName = prefs.getString(PREF_USERNAME, "").orEmpty()
        val lines = mutableListOf<String>()
        if (myName.isNotEmpty()) lines.add("$myName  (you)")
        activeUsers.keys.filter { it != myName }.forEach { lines.add(it) }
        tvUsers.text = lines.joinToString("\n") { "• $it" }
    }
}
