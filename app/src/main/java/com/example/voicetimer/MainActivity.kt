package com.example.voicetimer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var timerDisplay: TextView
    private lateinit var btnVoice: Button
    private lateinit var btnControl: Button
    private lateinit var prefs: SharedPreferences

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector

    companion object {
        private const val PREFS = "VoiceTimerPrefs"
        private const val KEY_RUNNING = "running"
        private const val KEY_PAUSED = "paused"
        private const val KEY_REMAINING = "remaining_seconds"
        private const val KEY_CONFIGURED = "configured_seconds"
        private const val KEY_ALARMING = "alarming"
        private const val KEY_END_ELAPSED = "end_elapsed"
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        checkPermissions()

        setupGestures()
        buildLayout()
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val isRunning = prefs.getBoolean(KEY_RUNNING, false)
                val isPaused = prefs.getBoolean(KEY_PAUSED, false)

                if (isRunning && !isPaused) {
                    speakCurrentRemaining()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val isRunning = prefs.getBoolean(KEY_RUNNING, false)

                if (isRunning) return false

                if (e1 != null) {
                    val dy = e1.y - e2.y
                    if (abs(dy) > 60 && abs(velocityY) > 80) {
                        if (dy > 0) {
                            adjustTime(300L)
                        } else {
                            adjustTime(-300L)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && gestureDetector.onTouchEvent(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(30, 40, 30, 40)
        }

        val hintText = TextView(this).apply {
            text = "【上滑+5分 / 下滑-5分 / 計時中點擊朗讀】"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }

        timerDisplay = TextView(this).apply {
            textSize = 86f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        btnVoice = Button(this).apply {
            text = "🎤 語音輸入時間"
            textSize = 20f
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(20, 10, 20, 15)
            }
            setOnClickListener {
                startSpeechRecognition()
            }
        }

        btnControl = Button(this).apply {
            text = "開始"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(20, 10, 20, 20)
            }
            setOnClickListener {
                handleControlClick()
            }
            setOnLongClickListener {
                val isPaused = prefs.getBoolean(KEY_PAUSED, false)
                if (isPaused) {
                    resetToConfigured()
                    Toast.makeText(
                        this@MainActivity,
                        "已重設為上次時間",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                } else {
                    false
                }
            }
        }

        root.addView(hintText)
        root.addView(timerDisplay)
        root.addView(btnVoice)
        root.addView(btnControl)

        setContentView(root)
    }

    private fun handleControlClick() {
        val isAlarming = prefs.getBoolean(KEY_ALARMING, false)
        val isRunning = prefs.getBoolean(KEY_RUNNING, false)
        val isPaused = prefs.getBoolean(KEY_PAUSED, false)

        when {
            isAlarming -> stopAlarmSound()

            isRunning && !isPaused -> {
                val intent = Intent(this, TimerService::class.java).apply {
                    action = TimerService.ACTION_PAUSE
                }
                startForegroundService(intent)
            }

            isPaused -> {
                val intent = Intent(this, TimerService::class.java).apply {
                    action = TimerService.ACTION_RESUME
                }
                startForegroundService(intent)
            }

            else -> {
                val configured = prefs.getLong(KEY_CONFIGURED, 300L)
                val intent = Intent(this, TimerService::class.java).apply {
                    action = TimerService.ACTION_START
                    putExtra(TimerService.EXTRA_SECONDS, configured)
                }
                startForegroundService(intent)
            }
        }
        refreshUi()
    }

    private fun stopAlarmSound() {
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_ALARM_STOP
        }
        ContextCompat.startForegroundService(this, intent)
        refreshUi()
    }

    private fun resetToConfigured() {
        val configured = prefs.getLong(KEY_CONFIGURED, 300L)
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .putBoolean(KEY_ALARMING, false)
            .putLong(KEY_REMAINING, configured)
            .apply()
        refreshUi()
    }

    private fun adjustTime(deltaSeconds: Long) {
        val configured = prefs.getLong(KEY_CONFIGURED, 300L)
        var newTime = configured + deltaSeconds
        newTime = newTime.coerceIn(60L, 10800L)

        prefs.edit()
            .putLong(KEY_CONFIGURED, newTime)
            .putLong(KEY_REMAINING, newTime)
            .apply()

        val m = newTime / 60
        speak("設定 $m 分鐘")
        refreshUi()
    }

    private fun handleSpokenTime(spoken: String) {
        var totalSec = 0L
        val clean = spoken.replace(" ", "").replace("個", "")

        val hourMatch = Regex("(\\d+)小時").find(clean)
        val minMatch = Regex("(\\d+)分").find(clean)
        val secMatch = Regex("(\\d+)秒").find(clean)
        val pureNumMatch = Regex("^(\\d+)$").find(clean)

        if (hourMatch != null) {
            totalSec += (hourMatch.groupValues[1].toLongOrNull() ?: 0L) * 3600L
        }
        if (minMatch != null) {
            totalSec += (minMatch.groupValues[1].toLongOrNull() ?: 0L) * 60L
        }
        if (secMatch != null) {
            totalSec += (secMatch.groupValues[1].toLongOrNull() ?: 0L)
        }
        if (hourMatch == null && minMatch == null && secMatch == null && pureNumMatch != null) {
            totalSec = (pureNumMatch.groupValues[1].toLongOrNull() ?: 1L) * 60L
        }

        if (totalSec <= 0L) {
            Toast.makeText(this, "未能辨識時間：「$spoken」", Toast.LENGTH_SHORT).show()
            return
        }

        totalSec = totalSec.coerceIn(1L, 10800L)

        prefs.edit()
            .putLong(KEY_CONFIGURED, totalSec)
            .putLong(KEY_REMAINING, totalSec)
            .apply()

        val m = totalSec / 60L
        val s = totalSec % 60L
        val startPrompt =
            if (m > 0L && s > 0L) "開始倒數 $m 分 $s 秒"
            else if (m > 0L) "開始倒數 $m 分鐘"
            else "開始倒數 $s 秒"
        speak(startPrompt)

        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_SECONDS, totalSec)
        }
        ContextCompat.startForegroundService(this, intent)
        refreshUi()
    }

    private fun speakCurrentRemaining() {
        val remaining = calculateCurrentRemaining()
        val m = remaining / 60L
        val s = remaining % 60L

        val text = if (m > 0L) {
            "還剩 $m 分鐘"
        } else {
            "還剩 $s 秒"
        }
        speak(text)
    }

    private fun calculateCurrentRemaining(): Long {
        val isRunning = prefs.getBoolean(KEY_RUNNING, false)
        if (!isRunning) {
            return prefs.getLong(
                KEY_REMAINING,
                prefs.getLong(KEY_CONFIGURED, 300L)
            )
        }
        val end = prefs.getLong(KEY_END_ELAPSED, 0L)
        if (end <= 0L) return prefs.getLong(KEY_REMAINING, 0L)
        return ((end - android.os.SystemClock.elapsedRealtime()) / 1000L)
            .coerceAtLeast(0L)
    }

    private fun refreshUi() {
        val isAlarming = prefs.getBoolean(KEY_ALARMING, false)
        val isRunning = prefs.getBoolean(KEY_RUNNING, false)
        val isPaused = prefs.getBoolean(KEY_PAUSED, false)
        val remaining = calculateCurrentRemaining()

        val mStr = String.format(Locale.TAIWAN, "%02d", remaining / 60)
        val sStr = String.format(Locale.TAIWAN, ":%02d", remaining % 60)
        val fullText = mStr + sStr

        val spannable = SpannableString(fullText).apply {
            setSpan(
                RelativeSizeSpan(0.33f),
                mStr.length,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        timerDisplay.text = spannable

        when {
            isAlarming -> {
                btnControl.text = "停止警報"
                btnControl.setBackgroundColor(Color.RED)
                btnVoice.isEnabled = false
                btnVoice.alpha = 0.3f
            }

            isRunning && !isPaused -> {
                btnControl.text = "暫停"
                btnControl.setBackgroundColor(Color.parseColor("#FF9500"))
                btnVoice.isEnabled = false
                btnVoice.alpha = 0.3f
            }

            isPaused -> {
                btnControl.text = "繼續 (長按重設)"
                btnControl.setBackgroundColor(Color.parseColor("#34C759"))
                btnVoice.isEnabled = true
                btnVoice.alpha = 1.0f
            }

            else -> {
                btnControl.text = "開始"
                btnControl.setBackgroundColor(Color.parseColor("#007AFF"))
                btnVoice.isEnabled = true
                btnVoice.alpha = 1.0f
            }
        }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "請說出時間，例如「5分鐘」或「3」"
            )
        }
        try {
            startActivityForResult(intent, 1001)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "裝置未支援或找不到語音識別服務",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "VoiceTimerMain"
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.TAIWAN
            ttsReady = true
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    @Deprecated("Use Activity Result API when migrating")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            handleSpokenTime(spoken)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
