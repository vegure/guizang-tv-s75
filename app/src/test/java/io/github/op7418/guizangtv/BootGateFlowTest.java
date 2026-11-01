package io.github.op7418.guizangtv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.PowerManager;
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

    @Test public void screenWakeCanReenterFromForegroundHookAfterThisBootWasConfirmed() {
        prefs.edit().putInt("attempted", 20).putInt("confirmed", 20).commit();
        Settings.System.putString(context.getContentResolver(), "start_3rd_app", context.getPackageName());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(3));
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIsScreenOn(false);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(power).setIsScreenOn(true);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(power.isInteractive());

        Activity gate = Robolectric.buildActivity(BootGateActivity.class).create().start().resume().get();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        Intent request = shadowOf(gate).getNextStartedActivity();
        assertNotNull("A real screen wake must reenter from the visible system hook even after boot confirmation", request);
        assertEquals(FrameHomeActivity.class.getName(), request.getComponent().getClassName());
        assertEquals(20, prefs.getInt("attempted", -1));
        assertEquals(20, prefs.getInt("confirmed", -1));
    }

    @Test public void hookCanWaitForScreenEventsDeliveredAfterItResumes() {
        completedBoot();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(gate.get().isFinishing());
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertNotNull(shadowOf(gate.get()).getNextStartedActivity());
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }

    @Test public void installingAfterTheBootWindowDoesNotBlockARealWake() {
        Settings.System.putString(context.getContentResolver(), "start_3rd_app", context.getPackageName());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(3));
        screenWake();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(shadowOf(gate.get()).getNextStartedActivity());
        assertEquals(-1, prefs.getInt("attempted", -1));
        assertEquals(-1, prefs.getInt("confirmed", -1));
        assertTrue(prefs.getLong("wake-attempted", -1) >= 0);
    }

    @Test public void pausedHookCannotLaunchWhenScreenEventsArriveLater() {
        completedBoot();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idle();
        gate.pause();
        screenWake();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
        assertEquals(-1, prefs.getLong("wake-attempted", -1));
    }

    @Test public void screenEventsAloneDoNotOpenAnActivity() {
        completedBoot();
        screenWake();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertEquals(-1, prefs.getLong("wake-attempted", -1));
    }

    @Test public void freshProcessDoesNotRecoverAWakeFromPreferencesOrOnAlone() {
        completedBoot();
        prefs.edit().putLong("wake-attempted", 100).putLong("wake-confirmed", 100)
                .putInt("wake-boot", 20).commit();
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
        assertEquals(100, prefs.getLong("wake-attempted", -1));
    }

    @Test public void duplicateOnDoesNotExtendTheWakeWindow() {
        completedBoot();
        screenWake();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29));
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }

    @Test public void anotherScreenOffCancelsThePendingWake() {
        completedBoot();
        screenWake();
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(Looper.getMainLooper()).idle();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }

    @Test public void nonInteractiveHookRejectsEvenAnOnBroadcast() {
        completedBoot();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsScreenOn(false);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        shadowOf(Looper.getMainLooper()).idle();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }

    @Test public void duplicateHooksConsumeOneWakeOnlyOnce() {
        completedBoot();
        screenWake();
        ActivityController<BootGateActivity> first = hook();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(shadowOf(first.get()).getNextStartedActivity());
        ActivityController<BootGateActivity> duplicate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(duplicate.get()).getNextStartedActivity());
    }

    @Test public void disablingAutoEntryCancelsAnObservedWake() {
        completedBoot();
        screenWake();
        prefs.edit().putBoolean("enabled", false).commit();
        ActivityController<BootGateActivity> disabled = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(disabled.get()).getNextStartedActivity());
        prefs.edit().putBoolean("enabled", true).commit();
        ActivityController<BootGateActivity> enabledAgain = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(enabledAgain.get()).getNextStartedActivity());
    }

    @Test public void anotherSystemTargetCancelsAnObservedWake() {
        completedBoot();
        screenWake();
        Settings.System.putString(context.getContentResolver(), "start_3rd_app", "another.package");
        ActivityController<BootGateActivity> otherTarget = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(otherTarget.get()).getNextStartedActivity());
        Settings.System.putString(context.getContentResolver(), "start_3rd_app", context.getPackageName());
        ActivityController<BootGateActivity> restoredTarget = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(restoredTarget.get()).getNextStartedActivity());
    }

    @Test public void lockedWakeWaitsForUnlockWithoutChangingBootConfirmation() {
        completedBoot();
        screenWake();
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
        shadowOf(users).setUserUnlocked(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertNotNull(shadowOf(gate.get()).getNextStartedActivity());
        assertEquals(20, prefs.getInt("attempted", -1));
        assertEquals(20, prefs.getInt("confirmed", -1));
    }

    @Test public void expiredWakeCannotLaunchAfterUnlock() {
        completedBoot();
        screenWake();
        shadowOf(users).setUserUnlocked(false);
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        shadowOf(users).setUserUnlocked(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertTrue(gate.get().isFinishing());
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }

    @Test public void lateScreenEventsCannotReopenAFinishedHook() {
        completedBoot();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(gate.get().isFinishing());
        screenWake();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(gate.get()).getNextStartedActivity());
    }

    @Test public void fullBootClaimWinsOverAnObservedWake() {
        Settings.System.putString(context.getContentResolver(), "start_3rd_app", context.getPackageName());
        screenWake();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idle();
        Intent bootRequest = shadowOf(gate.get()).getNextStartedActivity();
        assertNotNull(bootRequest);
        assertEquals(20, bootRequest.getIntExtra("guizang.boot-count", -1));
        assertEquals(-1, prefs.getLong("wake-attempted", -1));
        ActivityController<BootGateActivity> duplicate = hook();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull(shadowOf(duplicate.get()).getNextStartedActivity());
    }

    @Test public void reusedHomeConfirmsWakeOnlyAfterNewIntentAndResume() {
        completedBoot();
        prefs.edit().putString("source", "boot-source").putString("status", "boot-status").commit();
        ActivityController<FrameHomeActivity> home = Robolectric.buildActivity(FrameHomeActivity.class)
                .create().start().resume();
        home.pause();
        screenWake();
        ActivityController<BootGateActivity> gate = hook();
        shadowOf(Looper.getMainLooper()).idle();
        Intent request = shadowOf(gate.get()).getNextStartedActivity();
        assertNotNull(request);
        long wake = prefs.getLong("wake-attempted", -1);
        assertTrue(wake >= 0);
        assertEquals(-1, prefs.getLong("wake-confirmed", -1));
        home.newIntent(request);
        assertEquals(-1, prefs.getLong("wake-confirmed", -1));
        home.resume();
        assertEquals(wake, prefs.getLong("wake-confirmed", -1));
        assertEquals(20, prefs.getInt("attempted", -1));
        assertEquals(20, prefs.getInt("confirmed", -1));
        assertEquals("boot-source", prefs.getString("source", ""));
        assertEquals("boot-status", prefs.getString("status", ""));
        home.pause().stop().destroy();
    }

    private void completedBoot() {
        prefs.edit().putInt("attempted", 20).putInt("confirmed", 20).commit();
        Settings.System.putString(context.getContentResolver(), "start_3rd_app", context.getPackageName());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(3));
    }

    private ActivityController<BootGateActivity> hook() {
        return Robolectric.buildActivity(BootGateActivity.class).create().start().resume();
    }

    private void screenWake() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIsScreenOn(false);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(power).setIsScreenOn(true);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        shadowOf(Looper.getMainLooper()).idle();
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
