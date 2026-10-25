package io.github.op7418.guizangtv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibilityAccessibilityEvent;

/**
 * Boot-start via accessibility binding. The S75 firmware (OS3.0.106) marks
 * every third-party package stopped during shutdown, so BOOT_COMPLETED is
 * never delivered and appops grants are reset; an enabled accessibility
 * service is bound by the system after user unlock instead, which sidesteps
 * both defenses and carries a background-activity-launch exemption.
 *
 * Two triggers, both once-only and both gated by the in-app switch:
 * 1. Cold boot: the stock launcher window first appears in a new boot cycle
 *    (bookkept via Settings.Global.BOOT_COUNT, shared with BootGateActivity).
 * 2. Standby wake: this firmware re-fronts the stock launcher after the
 *    remote's power-button wake. When the launcher window appears shortly
 *    after ACTION_SCREEN_ON (a wake), open the desktop once per wake.
 *    A plain HOME press while the screen is already on never fires, so the
 *    Home key keeps returning to the stock launcher.
 */
public class BootAccessibilityService extends AccessibilityService {
    private static final String PREFS = "boot-start";
    private static final String EXTRA = "guizang.boot-count";
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private static final long WAKE_WINDOW_MS = 12_000;
    private static final long SETTLE_DELAY_MS = 3_000;

    private boolean handledBoot = false;
    private long screenOnAt = 0L;
    private long handledScreenOnAt = -1L;
    private BroadcastReceiver screenReceiver;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOnAt = SystemClock.elapsedRealtime();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    @Override
    public void onDestroy() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) { }
            screenReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !STOCK_HOME.contentEquals(pkg)) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return;

        if (!handledBoot) {
            int boot = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (boot >= 0 && prefs.getInt("attempted", -1) != boot) {
                handledBoot = true;
                // Record before launching so BootGateActivity cannot double-open.
                prefs.edit().putInt("attempted", boot)
                        .putString("status", "无障碍监听到桌面，正在直接启动")
                        .apply();
                launch("开机周期 " + boot, 4_000);
                return;
            }
            handledBoot = true;
        }

        // Standby wake: launcher re-fronted shortly after the screen came on.
        long now = SystemClock.elapsedRealtime();
        if (screenOnAt > 0 && handledScreenOnAt != screenOnAt
                && now >= screenOnAt && now - screenOnAt <= WAKE_WINDOW_MS) {
            handledScreenOnAt = screenOnAt;
            launch("待机唤醒", SETTLE_DELAY_MS);
        }
    }

    private void launch(String reason, long delayMs) {
        final long delay = delayMs;
        new Thread(() -> {
            try {
                // Give the freshly shown launcher a moment to settle before covering it.
                SystemClock.sleep(delay);
                Intent launchIntent = new Intent(getApplicationContext(), FrameHomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launchIntent);
                Log.i("GuiZangBoot", "无障碍直接启动完成（" + reason + "）");
            } catch (RuntimeException error) {
                Log.w("GuiZangBoot", "无障碍直接启动失败（" + reason + "）："
                        + error.getClass().getSimpleName());
            }
        }).start();
    }

    @Override
    public void onInterrupt() { }
}
