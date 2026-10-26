package io.github.op7418.guizangtv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

/** Direct-Boot-aware foreground entry for the stock launcher's package hook. */
public final class BootGateActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long deadline;
    private final Runnable awaitUnlock = new Runnable() {
        @Override public void run() {
            if (SystemClock.elapsedRealtime() >= deadline) {
                android.util.Log.w("GuiZangBoot", getPackageName() + " source=system-hook unlock timeout");
                finish();
            } else if (BootStart.unlocked(BootGateActivity.this)) {
                BootStart.enter(BootGateActivity.this);
                finish();
            } else handler.postDelayed(this, 300);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        deadline = SystemClock.elapsedRealtime() + 30_000;
        android.util.Log.i("GuiZangBoot", getPackageName() + " source=system-hook elapsed="
                + SystemClock.elapsedRealtime() + " unlocked=" + BootStart.unlocked(this));
        TextView message = new TextView(this);
        message.setText("正在启动归藏电视…");
        message.setTextColor(android.graphics.Color.WHITE);
        message.setGravity(Gravity.CENTER);
        setContentView(message);
    }
    @Override protected void onResume() {
        super.onResume();
        handler.post(awaitUnlock);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(awaitUnlock);
        super.onPause();
    }
    @Override protected void onStop() {
        handler.removeCallbacks(awaitUnlock);
        super.onStop();
        finish();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
