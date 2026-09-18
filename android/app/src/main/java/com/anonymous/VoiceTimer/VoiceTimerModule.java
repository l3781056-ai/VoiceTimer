package com.anonymous.VoiceTimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

public class VoiceTimerModule extends ReactContextBaseJavaModule {

    private static final int BASE_REQUEST_CODE = 300000;
    private static final int FINISH_REQUEST_CODE = 300999;

    private final ReactApplicationContext reactContext;

    public VoiceTimerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @NonNull
    @Override
    public String getName() {
        return "VoiceTimerModule";
    }

    @ReactMethod
    public void startTimer(double durationMillis) {

        long duration = (long) durationMillis;

        if (duration <= 0) {
            return;
        }

        AlarmManager alarmManager =
                (AlarmManager) reactContext.getSystemService(
                        Context.ALARM_SERVICE
                );

        if (alarmManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            if (!alarmManager.canScheduleExactAlarms()) {

                openExactAlarmSettings();
                return;
            }
        }

        cancelAllAlarms();

        long startTimeMillis = System.currentTimeMillis();
        long endTimeMillis = startTimeMillis + duration;

        int reminderIndex = 0;

        /*
         * 每 5 分鐘提醒一次。
         *
         * 例如 60 分鐘：
         *
         * 55、50、45、40、35、30、25、20、15、10、5
         */

        for (
                long remaining = 300000L;
                remaining < duration;
                remaining += 300000L
        ) {

            long triggerAtMillis =
                    endTimeMillis - remaining;

            if (triggerAtMillis <= startTimeMillis) {
                continue;
            }

            scheduleAlarm(
                    alarmManager,
                    triggerAtMillis,
                    BASE_REQUEST_CODE + reminderIndex,
                    VoiceTimerAlarmReceiver.ACTION_REMINDER,
                    (int) (remaining / 60000L)
            );

            reminderIndex++;

            if (reminderIndex >= 100) {
                break;
            }
        }

        // 0 分鐘：時間到了
        scheduleAlarm(
                alarmManager,
                endTimeMillis,
                FINISH_REQUEST_CODE,
                VoiceTimerAlarmReceiver.ACTION_FINISH,
                0
        );
    }

    private void scheduleAlarm(
            AlarmManager alarmManager,
            long triggerAtMillis,
            int requestCode,
            String action,
            int remainingMinutes
    ) {

        Intent intent = new Intent(
                reactContext,
                VoiceTimerAlarmReceiver.class
        );

        intent.setAction(action);

        intent.putExtra(
                "remainingMinutes",
                remainingMinutes
        );

        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(
                        reactContext,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {

            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );

        } else {

            alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );
        }
    }

    @ReactMethod
    public void stopTimer() {
        cancelAllAlarms();
    }

    private void cancelAllAlarms() {

        AlarmManager alarmManager =
                (AlarmManager) reactContext.getSystemService(
                        Context.ALARM_SERVICE
                );

        if (alarmManager == null) {
            return;
        }

        for (int i = 0; i < 100; i++) {

            Intent intent = new Intent(
                    reactContext,
                    VoiceTimerAlarmReceiver.class
            );

            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            reactContext,
                            BASE_REQUEST_CODE + i,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE
                    );

            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }

        Intent finishIntent = new Intent(
                reactContext,
                VoiceTimerAlarmReceiver.class
        );

        PendingIntent finishPendingIntent =
                PendingIntent.getBroadcast(
                        reactContext,
                        FINISH_REQUEST_CODE,
                        finishIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        alarmManager.cancel(finishPendingIntent);
        finishPendingIntent.cancel();
    }

    private void openExactAlarmSettings() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            try {

                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse(
                                "package:" +
                                reactContext.getPackageName()
                        )
                );

                intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                );

                reactContext.startActivity(intent);

            } catch (Exception e) {

                Intent intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse(
                                "package:" +
                                reactContext.getPackageName()
                        )
                );

                intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                );

                reactContext.startActivity(intent);
            }
        }
    }
}
