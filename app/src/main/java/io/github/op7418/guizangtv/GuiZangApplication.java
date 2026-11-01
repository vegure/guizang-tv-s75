package io.github.op7418.guizangtv;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

/** Process-local screen observation; receiving a broadcast never opens an Activity. */
public final class GuiZangApplication extends Application {
    private boolean observedScreenOff;
    private long pendingWakeAt = -1;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                observedScreenOff = true;
                pendingWakeAt = -1;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && observedScreenOff) {
                observedScreenOff = false;
                pendingWakeAt = SystemClock.elapsedRealtime();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
    }

    long takeWake() {
        long at = pendingWakeAt;
        pendingWakeAt = -1;
        long age = SystemClock.elapsedRealtime() - at;
        return at >= 0 && age >= 0 && age < 30_000 ? at : -1;
    }

    void cancelWake() { pendingWakeAt = -1; }
}
