package io.github.op7418.guizangtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Best-effort full-boot fallback; the foreground system hook remains primary. */
public class BootCompletedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // BOOT_COMPLETED itself is not a background-activity-launch exemption.
        // Do not claim this boot when Android would silently suppress the launch.
        if (!Settings.canDrawOverlays(context)) {
            Log.i("GuiZangBoot", context.getPackageName() + " source=boot-broadcast no background launch grant; skipped");
            return;
        }
        BootStart.enter(context, "boot-broadcast");
    }
}
