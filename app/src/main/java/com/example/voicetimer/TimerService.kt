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
import android.os.IBinder
import android.os.PowerManager
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

        private const val CHANNEL_ID = "voice_timer_running_v3"
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
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            pendingSpeech?.let {
                speakNow(it)
                pendingSpeech = null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .putLong(KEY_CONFIGURED, safeSeconds)
            .apply()

        startForeground(
            NOTIFICATION_ID,
            buildNotification("語音計時器正在倒數", formatTime(safeSeconds), true)
        )
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

        startForeground(
            NOTIFICATION_ID,
            buildNotification("3 分鐘提醒", "還剩 3 分鐘", true)
        )
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

        startForeground(
            NOTIFICATION_ID,
            buildNotification("語音計時器", "時間到了！碰觸螢幕或按鍵停止", false)
        )
        speak("時間到了！")
        playAlarmSound()
    }

    private fun stopAlarm() {
        cancelAlarms()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val configured = prefs.getLong(KEY_CONFIGURED, 300L).coerceIn(1L, 10800L)

        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .putBoolean(KEY_ALARMING, false)
            .putLong(KEY_REMAINING, configured)
            .remove(KEY_END_ELAPSED)
            .apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleAlarms(seconds: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAlarms()

        val now = SystemClock.elapsedRealtime()
        val finishAt = now + seconds * 1000L

        val finishIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_FINISH
            setPackage(packageName)
        }
        val finishPending = PendingIntent.getBroadcast(
            this,
            FINISH_REQUEST,
            finishIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setExact(alarmManager, finishAt, finishPending)

        if (seconds > 180L) {
            val reminderIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = ACTION_REMINDER
                setPackage(packageName)
            }
            val reminderPending = PendingIntent.getBroadcast(
                this,
                REMINDER_REQUEST,
                reminderIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setExact(alarmManager, finishAt - 180_000L, reminderPending)
        }
    }

    private fun setExact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    private fun cancelAlarms() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val reminderIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            setPackage(packageName)
        }
        val reminderPending = PendingIntent.getBroadcast(
            this,
            REMINDER_REQUEST,
            reminderIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (reminderPending != null) alarmManager.cancel(reminderPending)

        val finishIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_FINISH
            setPackage(packageName)
        }
        val finishPending = PendingIntent.getBroadcast(
            this,
            FINISH_REQUEST,
            finishIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (finishPending != null) alarmManager.cancel(finishPending)
    }

    private fun cancelReminderAlarmOnly() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            setPackage(packageName)
        }
        val pending = PendingIntent.getBroadcast(
            this,
            REMINDER_REQUEST,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) alarmManager.cancel(pending)
    }

    private fun readRunning(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

    private fun readRemaining(): Long {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            return prefs.getLong(KEY_REMAINING, prefs.getLong(KEY_CONFIGURED, 300L))
                .coerceAtLeast(1L)
        }

        val end = prefs.getLong(KEY_END_ELAPSED, 0L)
        if (end <= 0L) return prefs.getLong(KEY_REMAINING, 0L).coerceAtLeast(0L)

        val remaining = ((end - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
        prefs.edit().putLong(KEY_REMAINING, remaining).apply()
        return remaining
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)

        val stopIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val stopPending = PendingIntent.getActivity(
            this,
            3003,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(stopPending)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VoiceTimer 計時服務",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VoiceTimer 背景倒數服務"
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun speak(text: String) {
        if (ttsReady) {
            speakNow(text)
        } else {
            pendingSpeech = text
        }
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VoiceTimerSpeech")
    }

    private fun playAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) {
        }
    }

    private fun formatTime(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.TAIWAN, "%02d:%02d", minutes, secs)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
