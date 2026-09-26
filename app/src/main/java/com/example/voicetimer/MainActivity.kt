package com.example.voicetimer

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var display: TextView
    private lateinit var status: TextView
    private lateinit var voiceButton: Button
    private lateinit var toggleButton: Button
    private lateinit var resetButton: Button
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastConfiguredSeconds = 300L

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshFromSavedState()
            handler.postDelayed(this, 500L)
        }
    }

    companion object {
        private const val PREFS = "VoiceTimerPrefs"
        private const val KEY_CONFIGURED = "configured_seconds"
        private const val KEY_RUNNING = "running"
        private const val KEY_PAUSED = "paused"
        private const val KEY_REMAINING = "remaining_seconds"
        private const val KEY_END_ELAPSED = "end_elapsed"
        private const val SPEECH_REQUEST = 1001
        private const val NOTIFICATION_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastConfiguredSeconds = prefs.getLong(KEY_CONFIGURED, 300L).coerceIn(1L, 10800L)
        buildUi()
        tts = TextToSpeech(this, this)
        requestNotificationPermissionIfNeeded()
        refreshFromSavedState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTask)
        recoverServiceIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale.TAIWAN)
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }
        display = TextView(this).apply {
            textSize = 72f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
            setOnClickListener { speakRemaining() }
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        voiceButton = Button(this).apply {
            text = "語音輸入時間"
            textSize = 18f
            setOnClickListener { startSpeechInput() }
        }
        toggleButton = Button(this).apply {
            text = "開始"
            textSize = 18f
            setOnClickListener { toggleTimer() }
        }
        resetButton = Button(this).apply {
            text = "重設"
            textSize = 18f
            setOnClickListener { resetTimer() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(toggleButton)
        row.addView(resetButton)
        root.addView(display, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(voiceButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun toggleTimer() {
        val running = prefs.getBoolean(KEY_RUNNING, false)
        val paused = prefs.getBoolean(KEY_PAUSED, false)
        when {
            !running && !paused -> ensureExactAlarmPermission { startTimer(lastConfiguredSeconds) }
            running -> pauseTimer()
            paused -> ensureExactAlarmPermission { resumeTimer() }
        }
    }

    private fun startTimer(seconds: Long) {
        val safeSeconds = seconds.coerceIn(1L, 10800L)
        lastConfiguredSeconds = safeSeconds
        prefs.edit()
            .putLong(KEY_CONFIGURED, safeSeconds)
            .putLong(KEY_REMAINING, safeSeconds)
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_END_ELAPSED, android.os.SystemClock.elapsedRealtime() + safeSeconds * 1000L)
            .apply()
        startTimerService(TimerService.ACTION_START, safeSeconds)
        refreshFromSavedState()
    }

    private fun pauseTimer() {
        val remaining = currentRemainingSeconds()
        prefs.edit()
            .putLong(KEY_REMAINING, remaining)
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, true)
            .remove(KEY_END_ELAPSED)
            .apply()
        startTimerService(TimerService.ACTION_PAUSE)
        refreshFromSavedState()
    }

    private fun resumeTimer() {
        val remaining = prefs.getLong(KEY_REMAINING, lastConfiguredSeconds).coerceAtLeast(1L)
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_END_ELAPSED, android.os.SystemClock.elapsedRealtime() + remaining * 1000L)
            .apply()
        startTimerService(TimerService.ACTION_RESUME, remaining)
        refreshFromSavedState()
    }

    private fun resetTimer() {
        startTimerService(TimerService.ACTION_STOP)
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_REMAINING, lastConfiguredSeconds)
            .remove(KEY_END_ELAPSED)
            .apply()
        refreshFromSavedState()
    }

    private fun startTimerService(action: String, seconds: Long? = null) {
        val intent = Intent(this, TimerService::class.java).apply {
            this.action = action
            if (seconds != null) putExtra(TimerService.EXTRA_SECONDS, seconds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun recoverServiceIfNeeded() {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return
        val remaining = currentRemainingSeconds()
        if (remaining <= 0L) {
            resetTimer()
            return
        }
        startTimerService(TimerService.ACTION_RECOVER, remaining)
    }

    private fun currentRemainingSeconds(): Long {
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            return prefs.getLong(KEY_REMAINING, lastConfiguredSeconds).coerceAtLeast(0L)
        }
        val end = prefs.getLong(KEY_END_ELAPSED, 0L)
        if (end <= 0L) return prefs.getLong(KEY_REMAINING, lastConfiguredSeconds)
        return ((end - android.os.SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
    }

    private fun refreshFromSavedState() {
        lastConfiguredSeconds = prefs.getLong(KEY_CONFIGURED, lastConfiguredSeconds).coerceIn(1L, 10800L)
        val running = prefs.getBoolean(KEY_RUNNING, false)
        val paused = prefs.getBoolean(KEY_PAUSED, false)
        val remaining = currentRemainingSeconds()
        display.text = formatTime(remaining)
        when {
            running -> {
                status.text = "計時中（碰觸時間可朗讀）"
                toggleButton.text = "暫停"
                voiceButton.isEnabled = false
                voiceButton.alpha = 0.35f
            }
            paused -> {
                status.text = "已暫停"
                toggleButton.text = "繼續"
                voiceButton.isEnabled = true
                voiceButton.alpha = 1f
            }
            else -> {
                status.text = "準備就緒"
                toggleButton.text = "開始"
                voiceButton.isEnabled = true
                voiceButton.alpha = 1f
            }
        }
    }

    private fun startSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出時間，例如「5分鐘」、「1小時30分鐘」或「180」")
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (_: Exception) {
            Toast.makeText(this, "裝置不支援語音辨識", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST || resultCode != RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        handleSpokenTime(spoken)
    }

    private fun handleSpokenTime(spoken: String) {
        val clean = spoken.replace(" ", "").replace("個", "")
        var seconds = 0L
        Regex("(\\d+)小時").find(clean)?.let { seconds += (it.groupValues[1].toLongOrNull() ?: 0L) * 3600L }
        Regex("(\\d+)分鐘").find(clean)?.let { seconds += (it.groupValues[1].toLongOrNull() ?: 0L) * 60L }
        Regex("(\\d+)分").find(clean)?.let {
            if (!clean.contains("分鐘")) seconds += (it.groupValues[1].toLongOrNull() ?: 0L) * 60L
        }
        Regex("(\\d+)秒").find(clean)?.let { seconds += it.groupValues[1].toLongOrNull() ?: 0L }
        if (seconds == 0L) {
            val number = Regex("^(\\d+)$").find(clean)?.groupValues?.get(1)?.toLongOrNull()
            if (number != null) seconds = number * 60L
        }
        if (seconds <= 0L) {
            Toast.makeText(this, "無法辨識：「$spoken」", Toast.LENGTH_SHORT).show()
            return
        }
        if (seconds > 10800L) {
            seconds = 10800L
            Toast.makeText(this, "超過上限，已設定為 180 分鐘", Toast.LENGTH_SHORT).show()
        }
        lastConfiguredSeconds = seconds
        prefs.edit().putLong(KEY_CONFIGURED, seconds).putLong(KEY_REMAINING, seconds).apply()
        ensureExactAlarmPermission { startTimer(seconds) }
    }

    private fun speakRemaining() {
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            Toast.makeText(this, "目前沒有正在倒數", Toast.LENGTH_SHORT).show()
            return
        }
        speak(remainingPhrase(currentRemainingSeconds()))
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VoiceTimer_" + System.currentTimeMillis())
    }

    private fun remainingPhrase(seconds: Long): String {
        val minutes = seconds / 60L
        val secs = seconds % 60L
        return when {
            minutes > 0 && secs > 0 -> "還剩 $minutes 分 $secs 秒"
            minutes > 0 -> "還剩 $minutes 分鐘"
            else -> "還剩 $secs 秒"
        }
    }

    private fun ensureExactAlarmPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            then()
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) {
            then()
            return
        }
        Toast.makeText(this, "請允許「鬧鐘與提醒」，才能在休眠時準時提醒", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val h = safe / 3600L
        val m = (safe % 3600L) / 60L
        val s = safe % 60L
        return if (h > 0L) String.format(Locale.TAIWAN, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.TAIWAN, "%02d:%02d", m, s)
    }
}
