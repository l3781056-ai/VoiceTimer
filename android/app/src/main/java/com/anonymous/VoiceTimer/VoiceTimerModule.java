package com.anonymous.VoiceTimer;

import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

public class VoiceTimerModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;

    public VoiceTimerModule(
            ReactApplicationContext reactContext
    ) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @NonNull
    @Override
    public String getName() {
        return "VoiceTimerModule";
    }

    // =========================================================
    // 開始倒數
    // =========================================================

    @ReactMethod
    public void startTimer(double durationMillis) {

        long duration =
                (long) durationMillis;

        if (duration <= 0) {
            return;
        }

        Intent serviceIntent =
                new Intent(
                        reactContext,
                        VoiceTimerBackgroundService.class
                );

        serviceIntent.putExtra(
                "durationMillis",
                duration
        );

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            reactContext.startForegroundService(
                    serviceIntent
            );

        } else {

            reactContext.startService(
                    serviceIntent
            );
        }
    }

    // =========================================================
    // 停止倒數
    // =========================================================

    @ReactMethod
    public void stopTimer() {

        Intent serviceIntent =
                new Intent(
                        reactContext,
                        VoiceTimerBackgroundService.class
                );

        reactContext.stopService(
                serviceIntent
        );
    }
}
