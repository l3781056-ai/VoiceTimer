package com.example.voicetimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceTimer:AlarmWakeLock")
            // 持有 15 秒喚醒鎖，讓 CPU 保持清醒播放聲音，由系統超時自動釋放，避免被立即掐斷
            wl?.acquire(15_000L)
        } catch (_: Exception) {}

        try {
            val serviceIntent = Intent(context, TimerService::class.java).apply {
                this.action = action
                setPackage(context.packageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }
}
