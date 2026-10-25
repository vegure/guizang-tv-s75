package io.github.op7418.guizangtv;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Optional full-boot fallback when the user has enabled this service. */
public class BootAccessibilityService extends AccessibilityService {
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean waiting;
    private long deadline;
    private String foreground;
    private final Runnable awaitUnlock = new Runnable() {
        @Override public void run() {
            if (!STOCK_HOME.equals(foreground) || BootStart.skipReason(BootAccessibilityService.this) != null) {
                waiting = false;
                return;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                waiting = false;
                Log.w("GuiZangBoot", getPackageName() + " source=accessibility unlock timeout");
            } else if (BootStart.unlocked(BootAccessibilityService.this)) {
                waiting = false;
                BootStart.enter(BootAccessibilityService.this, "accessibility");
            } else handler.postDelayed(this, 300);
        }
    };
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) return;
        foreground = event.getPackageName().toString();
        if (!STOCK_HOME.equals(foreground)) {
            handler.removeCallbacks(awaitUnlock);
            waiting = false;
            return;
        }
        if (waiting || BootStart.skipReason(this) != null) return;
        waiting = true;
        deadline = SystemClock.elapsedRealtime() + 30_000;
        handler.post(awaitUnlock);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        waiting = false;
    }
}
