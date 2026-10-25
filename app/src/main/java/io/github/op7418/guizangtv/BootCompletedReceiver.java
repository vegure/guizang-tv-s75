package io.github.op7418.guizangtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Direct boot-start fallback for firmware whose stock launcher runs its own
 * auto-launch hook before credential-encrypted storage is unlocked. On
 * MiTV-MFTR0 / OS3.0.106 the launcher's openAutoApp resolved the target as
 * null six seconds after boot (user still locked) and crashed, so the
 * system start_3rd_app path never opens the desktop.
 *
 * Shares the in-app switch and once-per-boot bookkeeping with
 * BootGateActivity, then starts FrameHomeActivity directly after waiting
 * for user unlock inside the broadcast window. Needs the
 * SYSTEM_ALERT_WINDOW appop on Android 10+ to start from background.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String PREFS = "boot-start";
    private static final String EXTRA = "guizang.boot-count";
    private static final String ACTION_STR_BOOT = "mitv.action.STR_BOOT_COMPLETED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean fullBoot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean strWake = ACTION_STR_BOOT.equals(action);
        if (!fullBoot && !strWake) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return;
        int boot = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return;
        if (prefs.getInt("attempted", -1) == boot) return;
        // Record before launching so a system retry cannot double-open.
        prefs.edit().putInt("attempted", boot)
                .putString("status", "开机广播已接收，正在尝试直接启动")
                .apply();
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        final int bootForLaunch = boot;
        new Thread(() -> {
            try {
                // User unlock finishes a few seconds after BOOT_COMPLETED on
                // lock-free TV firmware; wait inside the broadcast window.
                SystemClock.sleep(7000);
                Intent launch = new Intent(app, FrameHomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA, bootForLaunch);
                app.startActivity(launch);
                Log.i("GuiZangBoot", "开机广播直接启动完成 boot=" + bootForLaunch);
            } catch (RuntimeException error) {
                Log.w("GuiZangBoot", "开机广播直接启动失败：" + error.getClass().getSimpleName());
            } finally {
                result.finish();
            }
        }).start();
    }
}
