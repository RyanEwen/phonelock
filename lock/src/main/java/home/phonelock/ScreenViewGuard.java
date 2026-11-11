package home.phonelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps "Parent screen view" (the {@link ScreenService} accessibility service) switched on.
 *
 * <p>Android periodically shows a "Review security &amp; privacy" notification about apps with accessibility
 * access, and newer versions let the user remove that access straight from it, without opening Settings
 * (which the lock blocks). The device owner can't stop that, but with WRITE_SECURE_SETTINGS (granted once over
 * adb at setup) this app can switch the service straight back on. Either way the parents are told.
 *
 * <p>While a parent has unlocked parent mode, a change is left alone: it was probably theirs. The check runs
 * again when parent mode ends ({@link Policy#apply}).
 */
final class ScreenViewGuard {
    private static ContentObserver observer;

    private ScreenViewGuard() {}

    /** Starts watching the accessibility settings (idempotent) and checks once now. */
    static synchronized void watch(Context c) {
        Context app = c.getApplicationContext();
        if (observer == null) {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    check(app);
                }
            };
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
        }
        check(app);
    }

    /** This app's service as it appears in the enabled list. */
    private static String component(Context c) {
        return c.getPackageName() + "/" + ScreenService.class.getName();
    }

    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean master = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        return master && on != null && on.contains(c.getPackageName() + "/");
    }

    /** Whether this app may switch the service back on by itself (granted at setup). */
    static boolean canRestore(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * If screen view was switched off outside parent mode, switch it back on (when allowed) and tell the
     * parents once per occurrence.
     */
    static void check(Context c) {
        SharedPreferences p = Policy.prefs(c);
        // Only once screen view has been set up: a device that never had it isn't "switched off".
        if (enabled(c)) {
            p.edit().putBoolean("screen_view_seen", true).putBoolean("screen_view_reported", false).apply();
            return;
        }
        if (!p.getBoolean("screen_view_seen", false) || !Policy.isOwner(c) || Policy.inParentMode(c)) return;

        boolean restored = canRestore(c) && restore(c);
        if (p.getBoolean("screen_view_reported", false)) return;
        p.edit().putBoolean("screen_view_reported", !restored).apply();
        Remote.event(c, restored
                ? "Screen view was switched off on the device (from Android's privacy review) and has been turned back on"
                : "Screen view was switched off on the device. Use Fix setup over Wi-Fi to turn it back on");
    }

    /** Puts this app's service back in the enabled list and turns accessibility on. */
    private static boolean restore(Context c) {
        try {
            String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String mine = component(c);
            String list = on == null || on.isEmpty() ? mine : (on.contains(c.getPackageName() + "/") ? on : on + ":" + mine);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(Policy.TAG, "screen view restored");
            return true;
        } catch (Exception e) {
            Log.w(Policy.TAG, "restore screen view: " + e);
            return false;
        }
    }
}
