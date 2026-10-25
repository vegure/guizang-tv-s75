package io.github.op7418.guizangtv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/** Opt-in, once-per-full-boot launch. Never handles screen-on, HDMI or Home keys. */
public final class BootStart {
    private static final String PREFS = "boot-start";
    private static final String EXTRA = "guizang.boot-count";
    // Every boot entry runs in the same process and uses this one DE store.
    private static synchronized SharedPreferences prefs(Context c) {
        SharedPreferences de = c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!de.getBoolean("ce-migrated", false) && unlocked(c)) {
            SharedPreferences ce = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = de.edit();
            // The old UI wrote only CE, so its switch is the last user choice.
            if (ce.contains("enabled")) edit.putBoolean("enabled", ce.getBoolean("enabled", false));
            int oldAttempt = ce.getInt("attempted", -1);
            if (oldAttempt >= de.getInt("attempted", -1)) {
                edit.putInt("attempted", oldAttempt);
                if (ce.contains("status")) edit.putString("status", ce.getString("status", ""));
            }
            if (edit.putBoolean("ce-migrated", true).commit()) ce.edit().clear().commit();
        }
        return de;
    }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static boolean unlocked(Context c) {
        UserManager users = (UserManager) c.getSystemService(Context.USER_SERVICE);
        return users != null && users.isUserUnlocked();
    }
    private static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }
    static String skipReason(Context c) {
        return BootStartPolicy.skipReason(XiaomiControls.enabled(), enabled(c), bootCount(c),
                prefs(c).getInt("attempted", -1), SystemClock.elapsedRealtime());
    }
    static synchronized void enter(Context c) { enter(c, "system-hook"); }
    static synchronized void enter(Context c, String source) {
        if (!unlocked(c)) { status(c, "系统尚未解锁，未发送启动请求"); return; }
        int boot = bootCount(c);
        String reason = skipReason(c);
        if (reason != null) { Log.i("GuiZangBoot", c.getPackageName() + " source=" + source + " boot=" + boot + " " + reason); return; }
        SharedPreferences state = prefs(c);
        if (!state.edit().putInt("attempted", boot)
                .putString("source", source)
                .putString("status", "已发送启动请求，等待界面确认").commit()) return;
        try {
            c.startActivity(new Intent(c, FrameHomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA, boot));
            Log.i("GuiZangBoot", c.getPackageName() + " source=" + source + " boot=" + boot + " 已发送启动请求");
        } catch (RuntimeException error) {
            // A rejected request must not consume the foreground system hook.
            state.edit().remove("attempted").commit();
            status(c, "启动失败：" + error.getClass().getSimpleName());
            Log.w("GuiZangBoot", c.getPackageName() + " source=" + source + " boot=" + boot + " Launch rejected", error);
        }
    }
    private static boolean configured(Context c) {
        return c.getPackageName().equals(Settings.System.getString(c.getContentResolver(), "start_3rd_app"));
    }
    private static void status(Context c, String value) {
        prefs(c).edit().putString("status", value).apply();
        Log.i("GuiZangBoot", c.getPackageName() + " source=" + prefs(c).getString("source", "none")
                + " boot=" + bootCount(c) + " " + value);
    }
    static void confirm(Activity activity) {
        Intent intent = activity.getIntent();
        int boot = intent.getIntExtra(EXTRA, -1);
        if (boot < 0) return;
        intent.removeExtra(EXTRA);
        if (boot == bootCount(activity) && prefs(activity).getInt("attempted", -1) == boot && enabled(activity)) {
            prefs(activity).edit().putInt("confirmed", boot).commit();
            status(activity, "已在本次开机后进入桌面");
        }
    }
    static void settings(Activity a, Runnable systemSettings) {
        new AlertDialog.Builder(a).setTitle("设置")
                .setItems(new String[]{"电视系统设置", "开机自动进入：" + (enabled(a) ? "已开启" : "已关闭")},
                        (d, i) -> { if (i == 0) systemSettings.run(); else show(a); })
                .setNegativeButton("返回", null).show();
    }
    static void show(Activity a) {
        if (!XiaomiControls.enabled()) {
            new AlertDialog.Builder(a).setTitle("开机自动进入（实验）")
                    .setMessage("目前适配 MiTV-MTSU0 / MiTV-MFTR0。其他机型请先使用普通应用入口。")
                    .setNegativeButton("返回", null).show();
            return;
        }
        String state = prefs(a).getString("status", "尚未进行完整重启测试");
        new AlertDialog.Builder(a).setTitle("开机自动进入（实验）")
                .setMessage("仅在完整开机后尝试一次，不接管 Home 键，同次开机的后续调用直接跳过。\n\n"
                        + "由已适配的小米系统调用启动入口；不需要悬浮窗权限。\n\n"
                        + "系统入口：" + (configured(a) ? "已配置" : "尚未配置，需电脑设置") + "\n上次状态：" + state)
                .setPositiveButton(enabled(a) ? "关闭自动进入" : "开启自动进入", (d, which) -> {
                    boolean value = !enabled(a);
                    prefs(a).edit().putBoolean("enabled", value).apply();
                    Toast.makeText(a, value ? (configured(a) ? "已开启，下次完整重启时尝试进入" : "开关已开启，还需按项目文档配置系统入口") : "已关闭开机自动进入", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("返回", null).show();
    }
    static void dump(Context c, java.io.PrintWriter out) {
        out.println("BootStart: package=" + c.getPackageName() + " enabled=" + enabled(c) + " configured=" + configured(c)
                + " boot=" + bootCount(c) + " attempted=" + prefs(c).getInt("attempted", -1)
                + " confirmed=" + prefs(c).getInt("confirmed", -1)
                + " source=" + prefs(c).getString("source", "none")
                + " status=" + prefs(c).getString("status", "none"));
    }
}
