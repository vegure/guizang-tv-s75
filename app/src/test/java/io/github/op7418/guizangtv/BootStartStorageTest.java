package io.github.op7418.guizangtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BootStartStorageTest {
    private Context context;
    private SharedPreferences ce;
    private SharedPreferences de;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        shadowOf((UserManager) context.getSystemService(Context.USER_SERVICE)).setUserUnlocked(true);
        ce = context.getSharedPreferences("boot-start", Context.MODE_PRIVATE);
        de = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("boot-start", Context.MODE_PRIVATE);
        ce.edit().clear().commit();
        de.edit().clear().commit();
    }

    @Test public void deviceChoiceRemainsReadableWithoutCredentialCopy() {
        de.edit().putBoolean("enabled", true).commit();
        assertTrue(BootStart.enabled(context));
    }

    @Test public void latestCredentialChoiceReplacesStaleDeviceCopy() {
        ce.edit().putBoolean("enabled", false).commit();
        de.edit().putBoolean("enabled", true).commit();
        assertFalse(BootStart.enabled(context));
        assertFalse(de.getBoolean("enabled", true));
        assertFalse(ce.contains("enabled"));
    }

    @Test public void migrationKeepsTheCurrentBootClaim() {
        ce.edit().putBoolean("enabled", true).putInt("attempted", 19).commit();
        de.edit().putBoolean("enabled", true).putInt("attempted", 20).commit();
        BootStart.enabled(context);
        java.io.StringWriter result = new java.io.StringWriter();
        BootStart.dump(context, new java.io.PrintWriter(result));
        assertTrue(result.toString(), result.toString().contains("attempted=20"));
    }

    @Test public void migratedPreferenceCannotReviveAnOldEnabledValue() {
        ce.edit().putBoolean("enabled", true).commit();
        assertTrue(BootStart.enabled(context));
        de.edit().putBoolean("enabled", false).commit();
        assertFalse(BootStart.enabled(context));
    }
}
