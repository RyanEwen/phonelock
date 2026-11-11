package home.phonelock;

import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Answers a parent's "Send diagnostics": a checklist of what the lock app needs (device owner, Doze
 * exemption, location, screen view, call roles, file access…), the state of its background work (hub
 * connection, location log, outbox, updates), and the app's own recent log. This replaces reaching for adb
 * when something looks wrong while the device is away from home.
 *
 * <p>Each check is {@code {name, ok, value}}: {@code ok} true/false for a requirement, absent for plain
 * information. The parent app shows them as they come, so the wording lives here, beside the code it
 * describes. Nothing secret goes in (no topic, key, PIN or contacts).
 */
final class Diagnostics {
    /** Log lines to include, and a cap on their size (the reply travels as an encrypted attachment). */
    private static final int LOG_LINES = 400;
    private static final int LOG_MAX_CHARS = 60_000;

    private Diagnostics() {}

    /** Builds the report and publishes it as {@code diag} for request {@code req}. Returns at once. */
    static void send(Context c, String req) {
        new Thread(() -> {
            try {
                JSONObject o = Remote.msg("diag");
                o.put("req", req);
                // The report can be shared as plain text, so the hub topic (half of what reaches this
                // device) is masked wherever an error message or log line quotes a URL.
                o.put("checks", new JSONArray(redact(c, checks(c).toString())));
                o.put("log", redact(c, log()));
                Remote.publish(c, o, null);
            } catch (Exception e) {
                Log.w(Policy.TAG, "diagnostics: " + e);
            }
        }, "diag").start();
    }

    /** {@code s} with the hub topic replaced by "(topic)". */
    private static String redact(Context c, String s) {
        String topic = RemoteService.topic(c);
        return topic.isEmpty() ? s : s.replace(topic, "(topic)");
    }

    // ---------- checklist ----------

    private static JSONArray checks(Context c) {
        JSONArray a = new JSONArray();
        SharedPreferences p = Policy.prefs(c);
        long now = System.currentTimeMillis();

        // Device and app.
        info(a, "Lock app version", String.valueOf(Updater.installedVersion(c)));
        info(a, "Android", Build.VERSION.RELEASE + " (" + Build.DISPLAY + ")");
        info(a, "Model", Build.MANUFACTURER + " " + Build.MODEL);
        info(a, "Up for", ago(SystemClock.elapsedRealtime()));
        check(a, "Device owner", Policy.isOwner(c), null);

        // Power and network.
        int level = Battery.level(c);
        info(a, "Battery", (level >= 0 ? level + "%" : "unknown") + (Battery.charging(c) ? ", charging" : ""));
        check(a, "Exempt from battery saver (Doze)",
                c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName()),
                "Without it, background work can be delayed while the device sits still");
        info(a, "Network", network(c));
        long hubAt = p.getLong("hub_connected_at", 0);
        info(a, "Hub connection", hubAt == 0 ? "never connected" : "connected " + ago(now - hubAt) + " ago");
        String hubError = p.getString("hub_error", null);
        if (hubError != null) info(a, "Last hub error", hubError);

        // Location.
        LocationManager lm = c.getSystemService(LocationManager.class);
        check(a, "Location on", lm.isLocationEnabled(), null);
        check(a, "Location permission (incl. background)",
                granted(c, android.Manifest.permission.ACCESS_FINE_LOCATION)
                        && granted(c, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), null);
        info(a, "Location provider", lm.hasProvider(LocationManager.FUSED_PROVIDER) ? "fused (Wi-Fi, cell and GPS)"
                : lm.hasProvider(LocationManager.GPS_PROVIDER) ? "GPS only" : "none");
        info(a, "Location log", LocationLog.summary(c));
        info(a, "Alerts waiting to send", String.valueOf(Outbox.pendingAlerts(c)));

        // Features that need a one-time grant at setup (tools/configure-lock.sh).
        check(a, "Screen view (accessibility service)", screenViewOn(c), "Re-run the setup script");
        check(a, "Can turn screen view back on by itself", ScreenViewGuard.canRestore(c), "Re-run the setup script");
        RoleManager rm = c.getSystemService(RoleManager.class);
        check(a, "Call screening role", rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING), "Re-run the setup script");
        check(a, "Call redirection role", rm.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION), "Re-run the setup script");
        check(a, "Call log access", CallLogFeed.permitted(c), "Re-run the setup script");
        check(a, "App use access", AppUsage.permitted(c), "Use Fix setup over Wi-Fi");
        check(a, "File access", FileManager.permitted(), "Re-run the setup script");
        check(a, "Do Not Disturb access (ring)",
                c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted(), "Re-run the setup script");

        // Updates and storage.
        long checked = p.getLong("update_checked", 0);
        info(a, "Update check", checked == 0 ? "never" : ago(now - checked) + " ago"
                + (Updater.readyVersion(c) > 0 ? ", version " + Updater.readyVersion(c) + " waiting for screen-off" : ""));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        info(a, "Free storage", (fs.getAvailableBytes() / (1024 * 1024)) + " MB");
        return a;
    }

    /** A requirement: {@code ok}, with a {@code hint} shown when it fails. */
    private static void check(JSONArray a, String name, boolean ok, String hint) {
        try {
            JSONObject o = new JSONObject().put("name", name).put("ok", ok);
            if (!ok && hint != null) o.put("value", hint);
            a.put(o);
        } catch (Exception ignored) {}
    }

    /** Plain information, neither good nor bad. */
    private static void info(JSONArray a, String name, String value) {
        try {
            a.put(new JSONObject().put("name", name).put("value", value));
        } catch (Exception ignored) {}
    }

    private static boolean granted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean screenViewOn(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/");
    }

    /** "Wi-Fi, internet working" / "mobile data, no internet" / "none". */
    private static String network(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null) return "none";
        String kind = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data" : "other";
        boolean works = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return kind + (works ? ", internet working" : ", no internet");
    }

    /** "3 min", "2 h 5 min", "4 d 1 h". */
    static String ago(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + " min";
        long h = min / 60;
        if (h < 48) return h + " h " + (min % 60) + " min";
        return (h / 24) + " d " + (h % 24) + " h";
    }

    // ---------- log ----------

    /**
     * The app's own recent log lines (apps may read their own log without any permission), newest last,
     * capped in size.
     */
    private static String log() {
        StringBuilder sb = new StringBuilder();
        try {
            Process proc = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", String.valueOf(LOG_LINES),
                    "--pid=" + android.os.Process.myPid()).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) sb.append(line).append('\n');
            }
            proc.waitFor();
        } catch (Exception e) {
            sb.append("Couldn't read the log: ").append(e);
        }
        return sb.length() > LOG_MAX_CHARS ? sb.substring(sb.length() - LOG_MAX_CHARS) : sb.toString();
    }
}
