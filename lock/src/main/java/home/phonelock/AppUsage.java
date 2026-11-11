package home.phonelock;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which apps she used and when, for the parents' timeline: read from Android's own record of apps coming to
 * and leaving the screen (UsageStatsManager), so it costs no extra battery. Read at each location tick and
 * sent with the location readings; nothing is stored here beyond a cursor.
 *
 * <p>Sessions shorter than a minute are left out (a glance isn't use), as are this app, the home screen
 * and system screens. Needs usage access (the GET_USAGE_STATS app-op), granted once at setup.
 *
 * <p>Session fields: {@code app} package, {@code label}, {@code t} start, {@code to} end (ms).
 */
final class AppUsage {
    private static final long MIN_SESSION_MS = 60_000;
    /** A gap shorter than this between two runs of the same app (a notification pulled down) is one session. */
    private static final long JOIN_GAP_MS = 30_000;
    private static final long KEEP_MS = 7 * 24 * 3600_000L;
    private static final Set<String> SKIP = new HashSet<>(java.util.Arrays.asList(
            "com.android.systemui", "android", "com.android.settings.intelligence",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller"));

    private AppUsage() {}

    /** Whether usage access is granted. */
    static boolean permitted(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Finished sessions that ended after {@code since} (ms), oldest first. A session still in progress isn't
     * included until it ends. Empty when usage access is missing.
     */
    static JSONArray sessionsSince(Context c, long since) {
        JSONArray out = new JSONArray();
        if (!permitted(c)) return out;
        long now = System.currentTimeMillis();
        long from = Math.max(since, now - KEEP_MS);
        try {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            // Start well before the cursor, so a long session that began before it is seen whole.
            UsageEvents events = usm.queryEvents(from - 24 * 3600_000L, now);
            // An app is in use from its first activity on screen until its last one leaves: moving between
            // its own screens (A paused, B resumed, A stopped) is the same session.
            Map<String, Long> openSince = new HashMap<>();
            Map<String, Set<String>> openActivities = new HashMap<>();
            Map<String, JSONObject> lastByApp = new HashMap<>();
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                String pkg = e.getPackageName();
                if (pkg == null || pkg.equals(c.getPackageName()) || SKIP.contains(pkg)) continue;
                int type = e.getEventType();
                String activity = String.valueOf(e.getClassName());
                Set<String> open = openActivities.computeIfAbsent(pkg, k -> new HashSet<>());
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    open.add(activity);
                    openSince.putIfAbsent(pkg, e.getTimeStamp());
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    open.remove(activity);
                    if (!open.isEmpty()) continue;
                    Long start = openSince.remove(pkg);
                    if (start == null) continue;
                    long end = e.getTimeStamp();
                    JSONObject prev = lastByApp.get(pkg);
                    if (prev != null && start - prev.optLong("to") < JOIN_GAP_MS) {
                        prev.put("to", end);
                    } else {
                        JSONObject s = new JSONObject().put("app", pkg).put("t", start).put("to", end);
                        lastByApp.put(pkg, s);
                        out.put(s);
                    }
                }
            }
        } catch (Exception ex) {
            Log.w(Policy.TAG, "usage: " + ex);
        }
        // Keep sessions that ended after the cursor and lasted long enough; add names.
        JSONArray kept = new JSONArray();
        for (int i = 0; i < out.length(); i++) {
            JSONObject s = out.optJSONObject(i);
            if (s.optLong("to") <= from || s.optLong("to") - s.optLong("t") < MIN_SESSION_MS) continue;
            try {
                s.put("label", Remote.label(c, s.optString("app")));
            } catch (Exception ignored) {}
            kept.put(s);
        }
        return kept;
    }

    /** End time of the newest session in {@code sessions}, or {@code fallback}. */
    static long newestEnd(JSONArray sessions, long fallback) {
        long max = fallback;
        for (int i = 0; i < sessions.length(); i++) max = Math.max(max, sessions.optJSONObject(i).optLong("to"));
        return max;
    }
}
