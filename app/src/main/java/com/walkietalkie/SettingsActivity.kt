package com.walkietalkie

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        // Allowlist: letters, digits, space, hyphen, underscore only.
        // Rejects null bytes, control characters, and Unicode bidirectional overrides.
        val SAFE_NAME_REGEX = Regex("^[A-Za-z0-9 _-]{1,20}$")
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var etUsername: EditText
    private lateinit var etGroup: EditText
    private lateinit var rgPttType: RadioGroup
    private lateinit var rbScreen: RadioButton
    private lateinit var rbVolumeUp: RadioButton
    private lateinit var rbVolumeDown: RadioButton
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)

        etUsername   = findViewById(R.id.et_username)
        etGroup      = findViewById(R.id.et_group)
        rgPttType    = findViewById(R.id.rg_ptt_type)
        rbScreen     = findViewById(R.id.rb_screen)
        rbVolumeUp   = findViewById(R.id.rb_volume_up)
        rbVolumeDown = findViewById(R.id.rb_volume_down)
        btnSave      = findViewById(R.id.btn_save)

        loadCurrentValues()

        btnSave.setOnClickListener { trySave() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadCurrentValues() {
        etUsername.setText(prefs.getString(MainActivity.PREF_USERNAME, ""))
        etGroup.setText(prefs.getString(MainActivity.PREF_GROUP, ""))

        when (prefs.getInt(MainActivity.PREF_PTT_TYPE, MainActivity.PTT_SCREEN)) {
            MainActivity.PTT_VOLUME_UP   -> rbVolumeUp.isChecked   = true
            MainActivity.PTT_VOLUME_DOWN -> rbVolumeDown.isChecked = true
            else                         -> rbScreen.isChecked     = true
        }
    }

    private fun trySave() {
        val username = etUsername.text.toString().trim()
        val group    = etGroup.text.toString().trim()

        if (username.isEmpty()) {
            etUsername.error = "Username is required"
            etUsername.requestFocus()
            return
        }
        if (username.length > 20) {
            etUsername.error = "Maximum 20 characters"
            etUsername.requestFocus()
            return
        }
        if (!SAFE_NAME_REGEX.matches(username)) {
            etUsername.error = "Only letters, digits, spaces, hyphens and underscores allowed"
            etUsername.requestFocus()
            return
        }
        if (group.isEmpty()) {
            etGroup.error = "Group name is required"
            etGroup.requestFocus()
            return
        }
        if (group.length > 20) {
            etGroup.error = "Maximum 20 characters"
            etGroup.requestFocus()
            return
        }
        if (!SAFE_NAME_REGEX.matches(group)) {
            etGroup.error = "Only letters, digits, spaces, hyphens and underscores allowed"
            etGroup.requestFocus()
            return
        }

        val pttType = when (rgPttType.checkedRadioButtonId) {
            R.id.rb_volume_up   -> MainActivity.PTT_VOLUME_UP
            R.id.rb_volume_down -> MainActivity.PTT_VOLUME_DOWN
            else                -> MainActivity.PTT_SCREEN
        }

        prefs.edit()
            .putString(MainActivity.PREF_USERNAME, username)
            .putString(MainActivity.PREF_GROUP, group)
            .putInt(MainActivity.PREF_PTT_TYPE, pttType)
            .apply()

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
