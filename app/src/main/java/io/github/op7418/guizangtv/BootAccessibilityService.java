package io.github.op7418.guizangtv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Boot-start via accessibility binding. The S75 firmware (OS3.0.106) marks
 * every third-party package stopped during shutdown, so BOOT_COMPLETED is
 * never delivered and appops grants are reset; an enabled accessibility
 * service is bound by the system instead, which sidesteps both defenses and
 * carries a background-activity-launch exemption.
 *
 * This firmware has no accessibility settings UI for third-party services
 * and later reverts adb-written enablement, so the binding window is short:
 * win the race by binding early (directBootAware) and launching immediately
 * once the user is unlocked, before the enablement is reverted and the
 * still-empty process is killed. Once FrameHomeActivity is foreground the
 * process survives the later unbind.
 *
 * Triggers, both once-only and gated by the in-app switch:
 * 1. Cold boot: stock launcher window first appears in a new boot cycle
 *    (bookkept via Settings.Global.BOOT_COUNT, shared with BootGateActivity).
 * 2. Standby wake: launcher window appears shortly after ACTION_SCREEN_ON.
 *    A plain HOME press (screen already on) never fires.
 */
public class BootAccessibilityService extends AccessibilityService {
    private static final String PREFS = "boot-start";
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private static final long WAKE_WINDOW_MS = 12_000;
    private static final long SETTLE_MS = 800;
    private static final long UNLOCK_TIMEOUT_MS = 30_000;

    private boolean handledBoot = false;
    private long screenOnAt = 0L;
    private long handledScreenOnAt = -1L;
    private BroadcastReceiver screenReceiver;

    /** May run before user unlock; prefer device-encrypted storage with CE fallback. */
    private SharedPreferences prefs() {
        SharedPreferences de = createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE);
        if (de.contains("enabled")) return de;
        try {
            SharedPreferences ce = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (ce.contains("enabled")) return ce;
        } catch (RuntimeException ignored) { }
        return de;
    }

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
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
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

        SharedPreferences prefs = prefs();
        if (!prefs.getBoolean("enabled", false)) return;

        if (!handledBoot) {
            handledBoot = true;
            int boot = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (boot >= 0 && prefs.getInt("attempted", -1) != boot) {
                saveAttempted(prefs, boot);
                launch("开机周期 " + boot);
                return;
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (screenOnAt > 0 && handledScreenOnAt != screenOnAt
                && now >= screenOnAt && now - screenOnAt <= WAKE_WINDOW_MS) {
            handledScreenOnAt = screenOnAt;
            launch("待机唤醒");
        }
    }

    private void saveAttempted(SharedPreferences prefs, int boot) {
        // Record before launching so BootGateActivity cannot double-open.
        prefs.edit().putInt("attempted", boot)
                .putString("status", "无障碍监听到桌面，正在直接启动").apply();
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("attempted", boot).apply();
        } catch (RuntimeException ignored) { }
    }

    private void launch(String reason) {
        new Thread(() -> {
            try {
                UserManager um = (UserManager) getSystemService(USER_SERVICE);
                long deadline = SystemClock.elapsedRealtime() + UNLOCK_TIMEOUT_MS;
                while (!um.isUserUnlocked()
                        && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(300);
                }
                // Minimal settle so the freshly shown launcher finishes drawing.
                SystemClock.sleep(SETTLE_MS);
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
