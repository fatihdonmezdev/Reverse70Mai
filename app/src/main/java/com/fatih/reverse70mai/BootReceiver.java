package com.fatih.reverse70mai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "Reverse70MaiBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "System event received: " + action + ", ensuring Reverse70MaiService is running...");

        Intent serviceIntent = new Intent(context, Reverse70MaiService.class);
        serviceIntent.setAction(action);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Reverse70MaiService from broadcast: " + t.getMessage(), t);
        }
    }
}
