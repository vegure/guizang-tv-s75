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
 * service is bound by the system instead (directBootAware, before user
 * unlock), which un-stops the package in time for the BOOT_COMPLETED
 * broadcast too - both channels fire.
 *
 * Triggers, once-only and gated by the in-app switch:
 * 1. Cold boot: stock launcher window first appears in a new boot cycle.
 * 2. Standby wake: this firmware always re-fronts the stock launcher on wake
 *    (even over a live foreground app), so watching for window changes is
 *    unreliable - instead, on ACTION_SCREEN_ON, re-open the desktop when the
 *    foreground before standby was the launcher or ourselves. Waking back
 *    into another app (e.g. a video player) is never hijacked. A plain HOME
 *    press (screen stays on) never fires.
 */
public class BootAccessibilityService extends AccessibilityService {
    private static final String PREFS = "boot-start";
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private static final long WAKE_DELAY_MS = 4_000;
    private static final long UNLOCK_TIMEOUT_MS = 30_000;

    private boolean handledBoot = false;
    private String lastForegroundPkg = null;
    private String foregroundAtScreenOff = null;
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
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    foregroundAtScreenOff = lastForegroundPkg;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn(SystemClock.elapsedRealtime());
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
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
        CharSequence seq = event.getPackageName();
        if (seq == null) return;
        String pkg = seq.toString();
        if (!getPackageName().equals(pkg)) lastForegroundPkg = pkg;

        if (!STOCK_HOME.equals(pkg)) return;
        SharedPreferences prefs = prefs();
        if (!prefs.getBoolean("enabled", false)) return;

        if (!handledBoot) {
            handledBoot = true;
            int boot = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (boot >= 0 && prefs.getInt("attempted", -1) != boot) {
                saveAttempted(prefs, boot);
                launch("开机周期 " + boot);
            }
        }
    }

    private void onScreenOn(long at) {
        final SharedPreferences prefs = prefs();
        if (!prefs.getBoolean("enabled", false)) return;
        if (handledScreenOnAt == at) return;
        final String resumeFrom = foregroundAtScreenOff;
        // Only when the pre-standby foreground was the launcher or ourselves;
        // never hijack waking back into another app.
        if (resumeFrom != null && !STOCK_HOME.equals(resumeFrom)
                && !getPackageName().equals(resumeFrom)) return;
        handledScreenOnAt = at;
        final long screenOnAt = at;
        new Thread(() -> {
            try {
                SystemClock.sleep(WAKE_DELAY_MS);
                // If another app took the screen after wake, leave it alone.
                String nowPkg = lastForegroundPkg;
                if (nowPkg != null && !STOCK_HOME.equals(nowPkg)
                        && !getPackageName().equals(nowPkg)) {
                    Log.i("GuiZangBoot", "唤醒后已有其他前台：" + nowPkg + "，跳过");
                    return;
                }
                if (!prefs().getBoolean("enabled", false)) return;
                UserManager um = (UserManager) getSystemService(USER_SERVICE);
                long deadline = SystemClock.elapsedRealtime() + UNLOCK_TIMEOUT_MS;
                while (um != null && !um.isUserUnlocked()
                        && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(300);
                }
                Intent launchIntent = new Intent(getApplicationContext(), FrameHomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launchIntent);
                Log.i("GuiZangBoot", "唤醒直接启动完成（待机前前台："
                        + (resumeFrom == null ? "未知" : resumeFrom) + "）");
            } catch (RuntimeException error) {
                Log.w("GuiZangBoot", "唤醒直接启动失败：" + error.getClass().getSimpleName());
            }
        }).start();
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
                while (um != null && !um.isUserUnlocked()
                        && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(300);
                }
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
