package com.example.voicetimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

class TimerService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    companion object {
        const val ACTION_START = "com.example.voicetimer.START"
        const val ACTION_PAUSE = "com.example.voicetimer.PAUSE"
        const val ACTION_RESUME = "com.example.voicetimer.RESUME"
        const val ACTION_STOP = "com.example.voicetimer.STOP"
        const val ACTION_RECOVER = "com.example.voicetimer.RECOVER"
        const val ACTION_REMINDER = "com.example.voicetimer.REMINDER"
        const val ACTION_FINISH = "com.example.voicetimer.FINISH"
        const val ACTION_ALARM_STOP = "com.example.voicetimer.ALARM_STOP"
        const val EXTRA_SECONDS = "seconds"
        private const val CHANNEL_ID = "voice_timer_running_v2"
        private const val NOTIFICATION_ID = 10
        private const val REMINDER_REQUEST = 2001
        private const val FINISH_REQUEST = 2002
        private const val PREFS = "VoiceTimerPrefs"
        private const val KEY_RUNNING = "running"
        private const val KEY_PAUSED = "paused"
        private const val KEY_REMAINING = "remaining_seconds"
        private const val KEY_END_ELAPSED = "end_elapsed"
        private const val KEY_CONFIGURED = "configured_seconds"
        private const val KEY_ALARMING = "alarming"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.TAIWAN)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            pendingSpeech?.let { speakNow(it); pendingSpeech = null }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startOrResume(intent.getLongExtra(EXTRA_SECONDS, 300L))
            ACTION_RESUME -> startOrResume(intent.getLongExtra(EXTRA_SECONDS, readRemaining()))
            ACTION_RECOVER -> {
                val seconds = readRemaining()
                if (readRunning() && seconds > 0L) startOrResume(seconds)
                else stopSelf()
            }
            ACTION_PAUSE -> pauseInternal()
            ACTION_STOP -> stopInternal()
            ACTION_REMINDER -> handleReminder()
            ACTION_FINISH -> handleFinish()
            ACTION_ALARM_STOP -> stopAlarm()
        }
        return START_NOT_STICKY
    }

    private fun startOrResume(seconds: Long) {
        val safeSeconds = seconds.coerceIn(1L, 10800L)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val end = SystemClock.elapsedRealtime() + safeSeconds * 1000L
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_PAUSED, false)
            .putBoolean(KEY_ALARMING, false)
            .putLong(KEY_REMAINING, safeSeconds)
            .putLong(KEY_END_ELAPSED, end)
            .apply()
        startForeground(NOTIFICATION_ID, buildNotification("語音計時器正在倒數", formatTime(safeSeconds)))
        scheduleAlarms(safeSeconds)
    }

    private fun pauseInternal() {
        val remaining = readRemaining()
        cancelAlarms()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, true)
            .putLong(KEY_REMAINING, remaining)
            .remove(KEY_END_ELAPSED)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopInternal() {
        cancelAlarms()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .remove(KEY_END_ELAPSED)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleReminder() {
        if (!readRunning()) { stopSelf(); return }
        val remaining = readRemaining()
        if (remaining <= 0L) { handleFinish(); return }
        startForeground(NOTIFICATION_ID, buildNotification("3 分鐘提醒", "還剩 3 分鐘", true))
        speak("還剩 3 分鐘")
        cancelReminderAlarmOnly()
    }

    private fun handleFinish() {
        cancelAlarms()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .putBoolean(KEY_ALARMING, true)
            .putLong(KEY_REMAINING, 0L)
            .remove(KEY_END_ELAPSED)
            .apply()
        startForeground(NOTIFICATION_ID, buildNotification("語音計時器", "時間到了！碰觸螢幕或按鍵停止", false))
        speak("時間到了！")
        playAlarmSound()
    }

    private fun stopAlarm() {
        cancelAlarms()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val configured = prefs.getLong(KEY_CONFIGURED, 300L).coerceIn(1L, 10800L)
        prefs.edit().putBoolean(KEY_RUNNING, false).putBoolean(KEY_PAUSED, false).putBoolean(KEY_ALARMING, false).putLong(KEY_REMAINING, configured).remove(KEY_END_ELAPSED).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }


