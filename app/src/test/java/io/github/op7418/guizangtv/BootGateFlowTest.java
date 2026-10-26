package io.github.op7418.guizangtv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBuild;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class BootGateFlowTest {
    private Context context;
    private SharedPreferences prefs;
    private UserManager users;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        shadowOf(users).setUserUnlocked(true);
        ShadowBuild.setModel("MiTV-MFTR0");
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 20);
        context.getSharedPreferences("boot-start", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("boot-start", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("enabled", true).putBoolean("ce-migrated", true).commit();
    }

    @Test public void foregroundHookWaitsUntilUnlockThenRequestsOnce() {
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> controller = Robolectric.buildActivity(BootGateActivity.class)
                .create().start().resume();
        Activity gate = controller.get();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(gate).getNextStartedActivity());
        assertEquals(-1, prefs.getInt("attempted", -1));
        shadowOf(users).setUserUnlocked(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        Intent request = shadowOf(gate).getNextStartedActivity();
        assertNotNull(request);
        assertEquals(FrameHomeActivity.class.getName(), request.getComponent().getClassName());
        assertEquals(20, request.getIntExtra("guizang.boot-count", -1));
        assertNull(shadowOf(gate).getNextStartedActivity());
        assertFalse(prefs.getString("status", "").contains("已在本次开机后进入桌面"));
    }

    @Test public void credentialOnlyUpgradeWaitsForUnlockBeforeReadingChoice() {
        prefs.edit().clear().commit();
        context.getSharedPreferences("boot-start", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", true).commit();
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> controller = Robolectric.buildActivity(BootGateActivity.class)
                .create().start().resume();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(controller.get().isFinishing());
        shadowOf(users).setUserUnlocked(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertNotNull(shadowOf(controller.get()).getNextStartedActivity());
    }

    @Test public void pausedHookCannotLaunchBeforeItStops() {
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> controller = Robolectric.buildActivity(BootGateActivity.class)
                .create().start().resume();
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause();
        shadowOf(users).setUserUnlocked(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
        assertEquals(-1, prefs.getInt("attempted", -1));
    }

    @Test public void resumingAfterDeadlineCannotLaunchEvenIfNowUnlocked() {
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> controller = Robolectric.buildActivity(BootGateActivity.class)
                .create().start().resume();
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        shadowOf(users).setUserUnlocked(true);
        controller.resume();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(controller.get().isFinishing());
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
        assertEquals(-1, prefs.getInt("attempted", -1));
    }

    @Test public void leavingTheHookCancelsPendingLaunch() {
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> controller = Robolectric.buildActivity(BootGateActivity.class)
                .create().start().resume();
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop();
        shadowOf(users).setUserUnlocked(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
        assertEquals(-1, prefs.getInt("attempted", -1));
    }

    @Test public void unlockTimeoutDoesNotClaimOrLaunch() {
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> controller = Robolectric.buildActivity(BootGateActivity.class)
                .create().start().resume();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        assertTrue(controller.get().isFinishing());
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
        assertEquals(-1, prefs.getInt("attempted", -1));
    }

    @Test public void receiverWithoutBackgroundGrantLeavesHookAvailable() {
        new BootCompletedReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(-1, prefs.getInt("attempted", -1));
        Activity gate = Robolectric.buildActivity(Activity.class).create().get();
        BootStart.enter(gate);
        BootStart.enter(gate);
        assertNotNull(shadowOf(gate).getNextStartedActivity());
        assertNull(shadowOf(gate).getNextStartedActivity());
    }
}
