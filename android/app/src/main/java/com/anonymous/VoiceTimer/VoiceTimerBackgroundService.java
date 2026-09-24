package com.anonymous.VoiceTimer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.net.Uri;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import java.util.Locale;

public class VoiceTimerBackgroundService extends Service {

    private static final String CHANNEL_ID = "VoiceTimerChannel";
    private static final int NOTIFICATION_ID = 1001;

    private static final int FINISH_REQUEST_CODE = 999999;
    private static final int REMINDER_BASE_REQUEST_CODE = 300000;

    private CountDownTimer countDownTimer;
    private CountDownTimer alertTimer;
    private MediaPlayer alarmPlayer;
    private TextToSpeech textToSpeech;
    private boolean textToSpeechReady = false;

    private long endTimeMillis = 0;
    private long totalDurationMillis = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeechReady = true;
                textToSpeech.setLanguage(Locale.TRADITIONAL_CHINESE);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        // =========================
        // 5 分鐘提醒
        // =========================
        if (VoiceTimerAlarmReceiver.ACTION_REMINDER.equals(action)) {

            startForeground(
                    NOTIFICATION_ID,
                    createNotification("VoiceTimer：5 分鐘提醒")
            );

            playAlarmFor60Seconds("還剩 5 分鐘", false);

            return START_NOT_STICKY;
        }

        // =========================
        // 倒數結束
        // =========================
        if (VoiceTimerAlarmReceiver.ACTION_FINISH.equals(action)) {

            startForeground(
                    NOTIFICATION_ID,
                    createNotification("VoiceTimer：倒數時間到了！")
            );

            cancelReminderAlarms();

            playAlarmFor60Seconds("時間到了，浩川祝你健康", true);

            return START_NOT_STICKY;
        }

        // =========================
        // 開始新的倒數
        // =========================
        long durationMillis =
                intent.getLongExtra("durationMillis", 0);

        if (durationMillis <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 如果原本已有倒數，先取消
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }

        cancelReminderAlarms();

        stopAlert();

        totalDurationMillis = durationMillis;
        endTimeMillis =
                System.currentTimeMillis() + durationMillis;

        startForeground(
                NOTIFICATION_ID,
                createNotification("VoiceTimer 正在倒數")
        );

        // =========================
        // App 前景顯示用倒數
        // Android AlarmManager 才是背景提醒的核心
        // =========================
        countDownTimer =
                new CountDownTimer(durationMillis, 1000) {

                    @Override
                    public void onTick(long millisUntilFinished) {

                        long remainingMillis =
                                endTimeMillis -
                                System.currentTimeMillis();

                        if (remainingMillis < 0) {
                            remainingMillis = 0;
                        }

                        long totalSeconds =
                                remainingMillis / 1000;

                        long minutes =
                                totalSeconds / 60;

                        long seconds =
                                totalSeconds % 60;

                        NotificationManager manager =
                                (NotificationManager)
                                        getSystemService(
                                                NOTIFICATION_SERVICE
                                        );

                        if (manager != null) {

                            manager.notify(
                                    NOTIFICATION_ID,
                                    createNotification(
                                            String.format(
                                                    "剩餘 %02d:%02d",
                                                    minutes,
                                                    seconds
                                            )
                                    )
                            );
                        }
                    }

                    @Override
                    public void onFinish() {

                        countDownTimer = null;

                        cancelReminderAlarms();

                        startForeground(
                                NOTIFICATION_ID,
                                createNotification(
                                        "VoiceTimer：倒數時間到了！"
                                )
                        );

                        playAlarmFor60Seconds(
                                "時間到了，浩川祝你健康",
                                true
                        );
                    }
                };

        countDownTimer.start();

        // =========================
        // 建立 5 分鐘提醒 + 結束提醒
        // =========================
        scheduleReminderAlarms(durationMillis);

        return START_STICKY;
    }

    // =========================================================
    // 排程提醒
    // =========================================================

    private void scheduleReminderAlarms(long durationMillis) {

        AlarmManager alarmManager =
                (AlarmManager)
                        getSystemService(
                                Context.ALARM_SERVICE
                        );

        if (alarmManager == null) {
            return;
        }

        long fiveMinutes =
                5 * 60 * 1000L;

        /*
         * 例如：
         * 20 分鐘
         * → 5 分鐘提醒
         * → 10 分鐘提醒
         * → 15 分鐘提醒
         *
         * 實際觸發時間：
         * 15 分鐘後
         * 10 分鐘後
         * 5 分鐘後
         */

        long elapsed = fiveMinutes;
        int reminderIndex = 1;

        while (elapsed < durationMillis) {

            long triggerTime =
                    System.currentTimeMillis()
                            + elapsed;

            scheduleAlarm(
                    alarmManager,
                    triggerTime,
                    VoiceTimerAlarmReceiver.ACTION_REMINDER,
                    REMINDER_BASE_REQUEST_CODE
                            + reminderIndex
            );

            elapsed += fiveMinutes;
            reminderIndex++;
        }

        // =========================
        // 最終結束 Alarm
        // =========================

        long finishTime =
                System.currentTimeMillis()
                        + durationMillis;

        scheduleAlarm(
                alarmManager,
                finishTime,
                VoiceTimerAlarmReceiver.ACTION_FINISH,
                FINISH_REQUEST_CODE
        );
    }

    private void scheduleAlarm(
            AlarmManager alarmManager,
            long triggerTime,
            String action,
            int requestCode
    ) {

        Intent intent =
                new Intent(
                        this,
                        VoiceTimerAlarmReceiver.class
                );

        intent.setAction(action);

        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(
                        this,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S) {

            if (!alarmManager.canScheduleExactAlarms()) {
                return;
            }
        }

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M) {

            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
            );

        } else {

            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
            );
        }
    }

    // =========================================================
    // 取消所有提醒
    // =========================================================

    private void cancelReminderAlarms() {

        AlarmManager alarmManager =
                (AlarmManager)
                        getSystemService(
                                Context.ALARM_SERVICE
                        );

        if (alarmManager == null) {
            return;
        }

        // 取消可能存在的 5 分鐘提醒
        for (int i = 1; i <= 100; i++) {

            Intent intent =
                    new Intent(
                            this,
                            VoiceTimerAlarmReceiver.class
                    );

            intent.setAction(
                    VoiceTimerAlarmReceiver.ACTION_REMINDER
            );

            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this,
                            REMINDER_BASE_REQUEST_CODE + i,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE
                    );

            alarmManager.cancel(pendingIntent);
        }

        // 取消結束 Alarm
        Intent finishIntent =
                new Intent(
                        this,
                        VoiceTimerAlarmReceiver.class
                );

        finishIntent.setAction(
                VoiceTimerAlarmReceiver.ACTION_FINISH
        );

        PendingIntent finishPendingIntent =
                PendingIntent.getBroadcast(
                        this,
                        FINISH_REQUEST_CODE,
                        finishIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        alarmManager.cancel(finishPendingIntent);
    }

    // =========================================================
    // 播放提醒聲音 60 秒
    // =========================================================

    private void playAlarmFor60Seconds(String message, boolean speakMessage) {

        stopAlert();

        if (speakMessage) {
            speakFinishMessage(message);
        }

        playAlarm();

        alertTimer =
                new CountDownTimer(
                        60 * 1000L,
                        1000
                ) {

                    @Override
                    public void onTick(
                            long millisUntilFinished
                    ) {
                    }

                    @Override
                    public void onFinish() {

                        stopAlarm();

                        alertTimer = null;

                        stopForeground(true);

                        stopSelf();
                    }
                };

        alertTimer.start();
    }

    private void speakFinishMessage(String message) {
        if (textToSpeech == null) {
            return;
        }

        if (!textToSpeechReady) {
            textToSpeech.setLanguage(Locale.TRADITIONAL_CHINESE);
            textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "finish_message");
            return;
        }

        textToSpeech.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "finish_message"
        );
    }

    // =========================================================
    // 播放鬧鐘聲
    // =========================================================

    private void playAlarm() {

        stopAlarm();

        try {

            Uri alarmUri =
                    android.media.RingtoneManager
                            .getDefaultUri(
                                    android.media.RingtoneManager
                                            .TYPE_ALARM
                            );

            if (alarmUri == null) {

                alarmUri =
                        android.media.RingtoneManager
                                .getDefaultUri(
                                        android.media.RingtoneManager
                                                .TYPE_NOTIFICATION
                                );
            }

            alarmPlayer =
                    new MediaPlayer();

            alarmPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(
                                    AudioAttributes.USAGE_ALARM
                            )
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .build()
            );

            alarmPlayer.setDataSource(
                    this,
                    alarmUri
            );

            alarmPlayer.setLooping(true);

            alarmPlayer.prepare();

            alarmPlayer.start();

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    // =========================================================
    // 停止提醒
    // =========================================================

    private void stopAlert() {

        if (alertTimer != null) {

            alertTimer.cancel();
            alertTimer = null;
        }

        stopAlarm();
    }

    private void stopAlarm() {

        if (alarmPlayer != null) {

            try {

                if (alarmPlayer.isPlaying()) {
                    alarmPlayer.stop();
                }

            } catch (Exception ignored) {
            }

            alarmPlayer.release();
            alarmPlayer = null;
        }
    }

    // =========================================================
    // Service 銷毀
    // =========================================================

    @Override
    public void onDestroy() {

        if (countDownTimer != null) {

            countDownTimer.cancel();
            countDownTimer = null;
        }

        if (alertTimer != null) {

            alertTimer.cancel();
            alertTimer = null;
        }

        cancelReminderAlarms();

        stopAlarm();

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
            textToSpeechReady = false;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =========================================================
    // Notification
    // =========================================================

    private Notification createNotification(String text) {

        return new Notification.Builder(
                this,
                CHANNEL_ID
        )
                .setContentTitle("VoiceTimer")
                .setContentText(text)
                .setSmallIcon(
                        android.R.drawable.ic_lock_idle_alarm
                )
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "VoiceTimer 背景倒數",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }
}
