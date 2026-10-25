package io.github.op7418.guizangtv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Boot-start via accessibility binding. The S75 firmware (OS3.0.106) marks
 * every third-party package stopped during shutdown, so BOOT_COMPLETED is
 * never delivered and appops grants are reset; an enabled accessibility
 * service is bound by the system after user unlock instead, which sidesteps
 * both defenses and carries a background-activity-launch exemption.
 *
 * When the stock launcher's window first appears in a new boot cycle and the
 * in-app switch is on, starts FrameHomeActivity once. Shares the
 * once-per-boot "attempted" bookkeeping with BootGateActivity.
 */
public class BootAccessibilityService extends AccessibilityService {
    private static final String PREFS = "boot-start";
    private static final String EXTRA = "guizang.boot-count";
    private boolean handled = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (handled || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.mitv.tvhome".contentEquals(pkg)) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return;
        int boot = Settings.Global.getInt(getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        if (boot < 0 || prefs.getInt("attempted", -1) == boot) return;
        handled = true;
        // Record before launching so BootGateActivity cannot double-open.
        prefs.edit().putInt("attempted", boot)
                .putString("status", "无障碍监听到桌面，正在直接启动")
                .apply();
        final int bootForLaunch = boot;
        new Thread(() -> {
            try {
                // Accessibility services bind after user unlock; give the
                // freshly shown launcher a moment to settle before covering it.
                SystemClock.sleep(4000);
                Intent launch = new Intent(getApplicationContext(), FrameHomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA, bootForLaunch);
                startActivity(launch);
                Log.i("GuiZangBoot", "无障碍直接启动完成 boot=" + bootForLaunch);
            } catch (RuntimeException error) {
                Log.w("GuiZangBoot", "无障碍直接启动失败：" + error.getClass().getSimpleName());
            }
        }).start();
    }

    @Override
    public void onInterrupt() { }
}
