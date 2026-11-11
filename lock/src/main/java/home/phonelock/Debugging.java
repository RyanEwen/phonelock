package home.phonelock;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;

/**
 * Recovery access for parents: switches USB and wireless debugging on for a while from the parent app, so
 * the device can be repaired or updated over adb even when its own screens can't be used (it froze, say).
 * Turns them off again when the time is up.
 *
 * <p>Needs WRITE_SECURE_SETTINGS, granted once over adb at setup; without it the parents are told to use
 * parent mode instead. Debugging also has to be allowed by policy (the "allow_debugging" setting lifts the
 * no-debugging restriction), which this turns on for the same time.
 */
final class Debugging {
    private Debugging() {}

    /** Turns debugging on for {@code minutes} and tells the parents where to connect. Blocks briefly. */
    static void enable(Context c, long minutes, String who) {
        if (!ScreenViewGuard.canRestore(c)) {
            Remote.event(c, "Couldn't turn on debugging: the device needs Fix setup over Wi-Fi first", who, false);
            return;
        }
        android.content.SharedPreferences p = Policy.prefs(c);
        android.content.SharedPreferences.Editor e = p.edit()
                .putLong("debug_until", System.currentTimeMillis() + minutes * 60_000L)
                .putBoolean("allow_debugging", true);
        // Remember the policy choice so it can be put back when the time is up: only when no window is open
        // already (extending one must not record "allowed" as the choice to go back to).
        if (!p.contains("debug_restore_allow")) e.putBoolean("debug_restore_allow", p.getBoolean("allow_debugging", true));
        e.apply();
        Policy.apply(c);
        try {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
        } catch (Exception ex) {
            Log.w(Policy.TAG, "debugging on: " + ex);
            Remote.event(c, "Couldn't turn on debugging: " + ex.getMessage(), who, false);
            return;
        }
        // Wireless debugging picks its port when it starts; give it a moment, then report where it is.
        try { Thread.sleep(3_000); } catch (InterruptedException ignored) {}
        String port = prop("service.adb.tls.port");
        String ip = wifiAddress(c);
        String where = ip != null && port != null && !port.isEmpty() && !port.equals("0")
                ? " Wireless debugging: " + ip + ":" + port + "." : " Wireless debugging is starting.";
        Remote.event(c, who + " turned on USB and wireless debugging until " + Remote.time(c, p.getLong("debug_until", 0))
                + "." + where, who, false);
    }

    /** Called on the service's timer: turns debugging off once the time a parent gave has run out. */
    static void expireIfDue(Context c) {
        android.content.SharedPreferences p = Policy.prefs(c);
        long until = p.getLong("debug_until", 0);
        if (until == 0 || System.currentTimeMillis() < until) return;
        disable(c);
        Remote.event(c, "Debugging turned off again", null, true);
    }

    /** Turns USB and wireless debugging off and puts the policy choice back. */
    static void disable(Context c) {
        android.content.SharedPreferences p = Policy.prefs(c);
        android.content.SharedPreferences.Editor e = p.edit().putLong("debug_until", 0);
        // Put the policy choice back only if a window recorded one; otherwise leave it as it is.
        if (p.contains("debug_restore_allow")) {
            e.putBoolean("allow_debugging", p.getBoolean("debug_restore_allow", true)).remove("debug_restore_allow");
        }
        e.apply();
        try {
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0);
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (Exception ex) {
            Log.w(Policy.TAG, "debugging off: " + ex);
        }
        Policy.apply(c);
    }

    /** A system property, read the way the shell does (apps can read most of them). */
    private static String prop(String name) {
        try {
            Process proc = new ProcessBuilder("getprop", name).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = r.readLine();
                return line == null ? null : line.trim();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The device's IPv4 address on its current network, or null. */
    private static String wifiAddress(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "address: " + e);
        }
        return null;
    }
}
