package com.example.voicetimer

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var display: TextView
    private lateinit var secondsDisplay: TextView
    private lateinit var status: TextView
    private lateinit var total: TextView
    private lateinit var control: Button
    private lateinit var voiceButton: Button
    private lateinit var timerRow: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val PREFS = "VoiceTimerPrefs"
        private const val RUNNING = "running"; private const val PAUSED = "paused"
        private const val REMAINING = "remaining_seconds"; private const val CONFIGURED = "configured_seconds"
        private const val ALARMING = "alarming"; private const val END = "end_elapsed"
        private const val TOTAL = "total_work_seconds"; private const val COUNT = "total_work_count"
        private const val INTERVAL = "setting_interval_remind_mode"
        private const val REQ_RECORD_AUDIO = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(16, 16, 16, 16)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, (48 * resources.displayMetrics.density).toInt())
        }

        val title = TextView(this).apply {
            text = "浩川計時器"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }

        val set = Button(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(8, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), -1)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        head.addView(title)
        head.addView(set)

        timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = 4
                bottomMargin = 4
            }
        }

        // 分鐘：主視覺，自然寬度居中
        display = TextView(this).apply {
            textSize = 88f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 秒數：適中字體緊鄰右側，自然居中
        secondsDisplay = TextView(this).apply {
            textSize = 40f
            setTextColor(Color.parseColor("#CCCCCC"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(8, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        timerRow.addView(display)
        timerRow.addView(secondsDisplay)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, (40 * resources.displayMetrics.density).toInt())
        }

        total = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val reset = Button(this).apply {
            text = "重置"
            textSize = 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -1)
            setOnClickListener {
                prefs.edit().putLong(TOTAL, 0).putLong(COUNT, 0).apply()
                updateUI()
            }
        }
        totalRow.addView(total)
        totalRow.addView(reset)

        voiceButton = Button(this).apply {
            text = "語音設定時間"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(12, 4, 12, 4)
            layoutParams = LinearLayout.LayoutParams(-1, (50 * resources.displayMetrics.density).toInt()).apply { bottomMargin = 4 }
            setOnClickListener { startVoiceRecognition() }
        }
        val voice = voiceButton

        status = TextView(this).apply {
            text = "準備就緒"
            textSize = 18f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4 }
        }

        control = Button(this).apply {
            text = "開始"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(12, 4, 12, 4)
            layoutParams = LinearLayout.LayoutParams(-1, (50 * resources.displayMetrics.density).toInt()).apply { topMargin = 0; bottomMargin = 4 }
            setOnClickListener { controlClick() }
        }

        bottom.addView(totalRow)
        bottom.addView(voice)
        bottom.addView(status)
        bottom.addView(control)

        root.addView(head)
        root.addView(timerRow)
        root.addView(bottom)
        setContentView(root)
        // 同時避開頂部狀態列與底部系統導覽列（白色橫條）
        root.setOnApplyWindowInsetsListener { v, insets ->
            val topInset = insets.systemWindowInsetTop
            val bottomInset = insets.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, topInset, v.paddingRight, bottomInset)
            insets
        }
        root.fitsSystemWindows = true

        timerRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> adjustTimerTextSize() }

        // 滑動手勢掛在 timerRow：左側 70% 區域加減 5 分鐘，右側 30% 秒區加減 1 分鐘
        timerRow.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; return true }
                    MotionEvent.ACTION_UP -> {
                        val dy = sy - e.y
                        val running = prefs.getBoolean(RUNNING, false)
                        if (running && !prefs.getBoolean(PAUSED, false) && abs(dy) < 60) {
                            speakRemaining()
                        } else if (!running && abs(dy) > 60) {
                            val d = if (sx < timerRow.width * 0.72f) 300L else 60L
                            adjust(if (dy > 0) d else -d)
                        }
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun adjustTimerTextSize() {
        if (!::timerRow.isInitialized || timerRow.width <= 0 || timerRow.height <= 0) return
        val density = resources.displayMetrics.scaledDensity
        val rowHeight = timerRow.height.toFloat()
        val minuteWidth = timerRow.width * 0.75f
        val maxByHeight = (rowHeight * 0.75f) / density
        val maxByWidth = (minuteWidth * 0.75f) / density
        val minuteSize = min(110f, min(maxByHeight, maxByWidth)).coerceAtLeast(36f)

        display.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, minuteSize)
        secondsDisplay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (minuteSize * 0.45f).coerceAtLeast(18f))
    }

    private fun controlClick() {
        when {
            prefs.getBoolean(ALARMING, false) -> send(TimerService.ACTION_ALARM_STOP)
            prefs.getBoolean(RUNNING, false) && !prefs.getBoolean(PAUSED, false) -> send(TimerService.ACTION_PAUSE)
            prefs.getBoolean(PAUSED, false) -> send(TimerService.ACTION_RESUME)
            else -> {
                val n = prefs.getLong(CONFIGURED, 300).coerceIn(1, 10800)
                speak("開始計時 " + speechTime(n))
                try { send(TimerService.ACTION_START, n) } catch (_: Exception) {}
            }
        }
    }

    private fun adjust(delta: Long) {
        val cur = prefs.getLong(CONFIGURED, 300)
        val target = (cur + delta).coerceIn(60, 10800)
        prefs.edit().putLong(CONFIGURED, target).putLong(REMAINING, target).apply()
        updateUI()
        speak(speechTime(target))
    }

    private fun speakRemaining() {
        val n = remaining()
        if (n > 0) speak("還剩 " + speechTime(n))
    }

    private fun updateUI() {
        val running = prefs.getBoolean(RUNNING, false)
        val paused = prefs.getBoolean(PAUSED, false)
        val alarming = prefs.getBoolean(ALARMING, false)
        val rem = remaining()

        val m = rem / 60
        val s = rem % 60
        display.text = String.format(Locale.TAIWAN, "%02d:", m)
        secondsDisplay.text = String.format(Locale.TAIWAN, "%02d", s)

        val totalSec = prefs.getLong(TOTAL, 0)
        val count = prefs.getLong(COUNT, 0)
        total.text = "累計: ${totalSec / 60}分 (${count}次)"

        val isIdle = !running && !paused
        voiceButton.isEnabled = isIdle
        voiceButton.alpha = if (isIdle) 1.0f else 0.4f

        when {
            alarming -> {
                status.text = "時間到了！"
                control.text = "停止響鈴"
            }
            running && !paused -> {
                status.text = "倒數中..."
                control.text = "暫停"
            }
            paused -> {
                status.text = "已暫停"
                control.text = "繼續"
            }
            else -> {
                status.text = "準備就緒"
                control.text = "開始"
            }
        }
    }

    private fun remaining(): Long {
        if (!prefs.getBoolean(RUNNING, false)) return prefs.getLong(REMAINING, prefs.getLong(CONFIGURED, 300))
        val e = prefs.getLong(END, 0)
        return if (e > 0) ((e - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0) else prefs.getLong(REMAINING, 0)
    }

    private fun send(action: String, seconds: Long = 0) {
        val intent = Intent(this, TimerService::class.java).apply {
            this.action = action
            if (seconds > 0) putExtra(TimerService.EXTRA_SECONDS, seconds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startVoiceRecognition() {
        val running = prefs.getBoolean(RUNNING, false)
        val paused = prefs.getBoolean(PAUSED, false)
        val alarming = prefs.getBoolean(ALARMING, false)
        if (running || paused || alarming) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出倒數時間（例如：5分鐘、10分半）")
            }
            startActivityForResult(intent, REQ_RECORD_AUDIO)
        } catch (_: Exception) {
            Toast.makeText(this, "語音辨識不可用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RECORD_AUDIO && resultCode == RESULT_OK) {
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull() ?: return
            val sec = parseVoiceText(text)
            if (sec > 0) {
                prefs.edit().putLong(CONFIGURED, sec).putLong(REMAINING, sec).apply()
                send(TimerService.ACTION_START, sec)
                updateUI()
                speak("已設定為 " + speechTime(sec) + "，開始計時")
            } else {
                speak("聽不清楚，請再試一次")
            }
        }
    }

    private fun parseVoiceText(text: String): Long {
        val regex = Regex("(\\d+)\\s*(分|分鐘)")
        val match = regex.find(text)
        if (match != null) {
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            return (min * 60).coerceIn(60, 10800)
        }
        return 0L
    }

    private fun speechTime(n: Long): String {
        val m = n / 60
        val s = n % 60
        return if (m > 0 && s > 0) "$m 分 $s 秒" else if (m > 0) "$m 分鐘" else "$s 秒"
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VoiceTimerSpeech")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.TAIWAN)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
