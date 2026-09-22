package com.anonymous.VoiceTimer;

import android.app.AlarmManager;
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !alarmManager.canScheduleExactAlarms()) {

            openExactAlarmSettings();
            return;
        }

        Intent serviceIntent =
                new Intent(
                        reactContext,
                        VoiceTimerBackgroundService.class
                );

        serviceIntent.setAction(
                VoiceTimerAlarmReceiver.ACTION_START
        );

        serviceIntent.putExtra(
                "durationMillis",
                duration
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(serviceIntent);
        } else {
            reactContext.startService(serviceIntent);
        }
    }

    @ReactMethod
    public void stopTimer() {

        Intent serviceIntent =
                new Intent(
                        reactContext,
                        VoiceTimerBackgroundService.class
                );

        serviceIntent.setAction(
                VoiceTimerAlarmReceiver.ACTION_STOP
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(serviceIntent);
        } else {
            reactContext.startService(serviceIntent);
        }
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
