package com.anonymous.VoiceTimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class VoiceTimerAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_REMINDER =
            "com.anonymous.VoiceTimer.REMINDER";

    public static final String ACTION_FINISH =
            "com.anonymous.VoiceTimer.FINISH";

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null) {
            return;
        }

        Intent serviceIntent = new Intent(
                context,
                VoiceTimerBackgroundService.class
        );

        String action = intent.getAction();

        if (ACTION_REMINDER.equals(action)) {
            serviceIntent.setAction(ACTION_REMINDER);

        } else if (ACTION_FINISH.equals(action)) {
            serviceIntent.setAction(ACTION_FINISH);

        } else {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
