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
        const val EXTRA_SECONDS = "seconds"
        private const val CHANNEL_ID = "voice_timer_running"
        private const val NOTIFICATION_ID = 10
        private const val REMINDER_REQUEST = 2001
        private const val FINISH_REQUEST = 2002
        private const val PREFS = "VoiceTimerPrefs"
        private const val KEY_RUNNING = "running"
        private const val KEY_PAUSED = "paused"
        private const val KEY_REMAINING = "remaining_seconds"
        private const val KEY_END_ELAPSED = "end_elapsed"
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
        if (!readRunning()) {
            stopSelf()
            return
        }
        val remaining = readRemaining()
        if (remaining <= 0L) {
            handleFinish()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("5 分鐘提醒", "還剩 5 分鐘"))
        vibrate()
        speak("還剩 5 分鐘")
    }

    private fun handleFinish() {
        cancelAlarms()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_REMAINING, 0L)
            .remove(KEY_END_ELAPSED)
            .apply()
        startForeground(NOTIFICATION_ID, buildNotification("語音計時器", "時間到了！"))
        vibrate()
        speak("時間到了！")
        playAlarmSound()
        Handler(mainLooper).postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 2500L)
    }

    private fun scheduleAlarms(seconds: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) return
        cancelAlarms()
        val endElapsed = SystemClock.elapsedRealtime() + seconds * 1000L
        if (seconds > 300L) scheduleExact(alarmManager, endElapsed - 300_000L, ACTION_REMINDER, REMINDER_REQUEST)
        scheduleExact(alarmManager, endElapsed, ACTION_FINISH, FINISH_REQUEST)
    }

    private fun scheduleExact(alarmManager: AlarmManager, triggerAt: Long, action: String, requestCode: Int) {
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            this.action = action
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun cancelAlarms() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(ACTION_REMINDER to REMINDER_REQUEST, ACTION_FINISH to FINISH_REQUEST).forEach { (action, requestCode) ->
            val intent = Intent(this, AlarmReceiver::class.java).apply {
                this.action = action
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun readRunning(): Boolean = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

    private fun readRemaining(): Long {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_RUNNING, false)) return prefs.getLong(KEY_REMAINING, 0L).coerceAtLeast(0L)
        val end = prefs.getLong(KEY_END_ELAPSED, 0L)
        if (end <= 0L) return prefs.getLong(KEY_REMAINING, 0L)
        return ((end - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
    }

    private fun speak(text: String) {
        if (ttsReady) speakNow(text) else pendingSpeech = text
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VoiceTimer_" + System.currentTimeMillis())
    }

    private fun vibrate() {
        val vibrator = getSystemService(android.os.Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 500), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 300, 200, 300, 200, 500), -1)
        }
    }

    private fun playAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        val channel = NotificationChannel(CHANNEL_ID, "語音計時器", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "倒數計時與時間提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            setSound(sound, attributes)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(this, 3001, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(readRunning())
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val h = safe / 3600L
        val m = (safe % 3600L) / 60L
        val s = safe % 60L
        return if (h > 0L) String.format(Locale.TAIWAN, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.TAIWAN, "%02d:%02d", m, s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
