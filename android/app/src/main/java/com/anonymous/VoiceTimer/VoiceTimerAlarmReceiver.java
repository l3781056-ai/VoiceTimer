package com.anonymous.VoiceTimer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;

public class VoiceTimerAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_REMINDER =
            "com.anonymous.VoiceTimer.REMINDER";

    public static final String ACTION_FINISH =
            "com.anonymous.VoiceTimer.FINISH";

    private static final String CHANNEL_ID =
            "voice_timer_alarm";

    private static final int NOTIFICATION_ID = 5001;

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null) {
            return;
        }

        String action = intent.getAction();

        if (!ACTION_REMINDER.equals(action)
                && !ACTION_FINISH.equals(action)) {
            return;
        }

        int remainingMinutes =
                intent.getIntExtra(
                        "remainingMinutes",
                        0
                );

        createNotificationChannel(context);

        NotificationManager notificationManager =
                (NotificationManager)
                        context.getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (notificationManager == null) {
            return;
        }

        String title;
        String message;

        if (ACTION_FINISH.equals(action)) {

            title = "⏰ VoiceTimer";
            message = "倒數時間到了！";

        } else {

            title = "⏰ VoiceTimer 倒數提醒";

            message =
                    "還剩下 "
                            + remainingMinutes
                            + " 分鐘";
        }

        Intent openIntent =
                context.getPackageManager()
                        .getLaunchIntentForPackage(
                                context.getPackageName()
                        );

        PendingIntent openPendingIntent = null;

        if (openIntent != null) {

            openIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            );

            openPendingIntent =
                    PendingIntent.getActivity(
                            context,
                            6000,
                            openIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE
                    );
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(
                        context,
                        CHANNEL_ID
                )
                        .setSmallIcon(
                                android.R.drawable.ic_lock_idle_alarm
                        )
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(
                                NotificationCompat.PRIORITY_MAX
                        )
                        .setCategory(
                                NotificationCompat.CATEGORY_ALARM
                        )
                        .setAutoCancel(true)
                        .setVisibility(
                                NotificationCompat.VISIBILITY_PUBLIC
                        )
                        .setDefaults(
                                NotificationCompat.DEFAULT_ALL
                        );

        if (openPendingIntent != null) {
            builder.setContentIntent(openPendingIntent);
        }

        notificationManager.notify(
                NOTIFICATION_ID,
                builder.build()
        );

        vibrate(context);
    }

    private void createNotificationChannel(Context context) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager notificationManager =
                (NotificationManager)
                        context.getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (notificationManager == null) {
            return;
        }

        Uri soundUri =
                android.provider.Settings.System
                        .DEFAULT_ALARM_ALERT_URI;

        AudioAttributes audioAttributes =
                new AudioAttributes.Builder()
                        .setUsage(
                                AudioAttributes.USAGE_ALARM
                        )
                        .setContentType(
                                AudioAttributes
                                        .CONTENT_TYPE_SONIFICATION
                        )
                        .build();

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "VoiceTimer 鬧鐘提醒",
                        NotificationManager.IMPORTANCE_HIGH
                );

        channel.setDescription(
                "VoiceTimer 每 5 分鐘及結束提醒"
        );

        channel.enableVibration(true);

        channel.setVibrationPattern(
                new long[]{0, 800, 400, 800}
        );

        channel.setSound(
                soundUri,
                audioAttributes
        );

        channel.setLockscreenVisibility(
                android.app.Notification.VISIBILITY_PUBLIC
        );

        notificationManager.createNotificationChannel(
                channel
        );
    }

    private void vibrate(Context context) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            VibratorManager vibratorManager =
                    (VibratorManager)
                            context.getSystemService(
                                    Context.VIBRATOR_MANAGER_SERVICE
                            );

            if (vibratorManager != null) {

                Vibrator vibrator =
                        vibratorManager.getDefaultVibrator();

                vibrator.vibrate(
                        VibrationEffect.createWaveform(
                                new long[]{0, 800, 400, 800},
                                -1
                        )
                );
            }

        } else {

            Vibrator vibrator =
                    (Vibrator)
                            context.getSystemService(
                                    Context.VIBRATOR_SERVICE
                            );

            if (vibrator != null) {

                if (Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O) {

                    vibrator.vibrate(
                            VibrationEffect.createWaveform(
                                    new long[]{0, 800, 400, 800},
                                    -1
                            )
                    );

                } else {

                    vibrator.vibrate(
                            new long[]{0, 800, 400, 800},
                            -1
                    );
                }
            }
        }
    }
}
