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
 * <p>Session fields: {@code app} package, {@code label}, {@code t} start, {@code to} end (ms). The same record
 * also feeds {@link AppTime}'s daily limits and totals through {@link #intervals}.
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

    /** Receives each stretch an app was on screen: {@code end} is "now" for one still open. */
    private interface Visit {
        void on(String pkg, long start, long end) throws Exception;
    }

    /**
     * Walks Android's record of apps coming to and leaving the screen between {@code from} and {@code to},
     * reporting each stretch an app was in use: from its first activity on screen until its last one leaves
     * (moving between its own screens, A paused, B resumed, A stopped, is one stretch). With
     * {@code includeOpen}, an app still on screen at {@code to} is reported up to {@code to}. Skips this app
     * and system screens. Stretches may start before {@code from}; callers clip them.
     */
    private static void walk(Context c, long from, long to, boolean includeOpen, Visit visit) throws Exception {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        UsageEvents events = usm.queryEvents(from, to);
        Map<String, Long> openSince = new HashMap<>();
        Map<String, Set<String>> openActivities = new HashMap<>();
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
                if (start != null) visit.on(pkg, start, e.getTimeStamp());
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                // Screen off ends whatever was showing, even if the app's own pause event never comes.
                for (Map.Entry<String, Long> o : openSince.entrySet()) visit.on(o.getKey(), o.getValue(), e.getTimeStamp());
                openSince.clear();
                openActivities.clear();
            }
        }
        if (includeOpen) for (Map.Entry<String, Long> o : openSince.entrySet()) visit.on(o.getKey(), o.getValue(), to);
    }

    /**
     * Each app's stretches on screen within [{@code from}, {@code to}], clipped to that window, including one
     * still in progress. For time limits and daily totals. Empty when usage access is missing.
     */
    static Map<String, java.util.List<long[]>> intervals(Context c, long from, long to) {
        Map<String, java.util.List<long[]>> out = new HashMap<>();
        if (!permitted(c) || to <= from) return out;
        try {
            // Start a few hours early so a stretch that began before the window is seen.
            walk(c, from - 6 * 3600_000L, to, true, (pkg, start, end) -> {
                long s = Math.max(start, from), e = Math.min(end, to);
                if (e > s) out.computeIfAbsent(pkg, k -> new java.util.ArrayList<>()).add(new long[]{s, e});
            });
        } catch (Exception ex) {
            Log.w(Policy.TAG, "usage intervals: " + ex);
        }
        return out;
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
        Map<String, JSONObject> lastByApp = new HashMap<>();
        try {
            // Start well before the cursor, so a long session that began before it is seen whole.
            walk(c, from - 24 * 3600_000L, now, false, (pkg, start, end) -> {
                JSONObject prev = lastByApp.get(pkg);
                if (prev != null && start - prev.optLong("to") < JOIN_GAP_MS) {
                    prev.put("to", end);
                } else {
                    JSONObject s = new JSONObject().put("app", pkg).put("t", start).put("to", end);
                    lastByApp.put(pkg, s);
                    out.put(s);
                }
            });
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

    /**
     * The app on screen now: {@code {app, since}} where {@code since} is when it was opened (a short gap,
     * such as pulling down a notification, doesn't restart it). Null when no app is in front (the screen is
     * off or the lock app's home screen is showing) or usage access is missing. For parents' status card.
     */
    static JSONObject current(Context c) {
        if (!permitted(c)) return null;
        long now = System.currentTimeMillis();
        Map<String, java.util.List<long[]>> stretches = new HashMap<>();
        try {
            walk(c, now - 6 * 3600_000L, now, true, (pkg, start, end) ->
                    stretches.computeIfAbsent(pkg, k -> new java.util.ArrayList<>()).add(new long[]{start, end}));
        } catch (Exception ex) {
            Log.w(Policy.TAG, "usage now: " + ex);
            return null;
        }

        // The one still open (it ends at "now"), the most recently opened if Android reports several.
        String app = null;
        long since = 0;
        for (Map.Entry<String, java.util.List<long[]>> e : stretches.entrySet()) {
            for (long[] s : e.getValue()) {
                if (s[1] == now && s[0] > since) {
                    app = e.getKey();
                    since = s[0];
                }
            }
        }
        if (app == null) return null;

        // Reach back over brief gaps in the same app, so it reads as one visit.
        boolean extended = true;
        while (extended) {
            extended = false;
            for (long[] s : stretches.get(app)) {
                if (s[0] < since && s[1] >= since - JOIN_GAP_MS) {
                    since = s[0];
                    extended = true;
                }
            }
        }
        try {
            return new JSONObject().put("app", app).put("since", since);
        } catch (Exception ex) {
            return null;
        }
    }

    /** End time of the newest session in {@code sessions}, or {@code fallback}. */
    static long newestEnd(JSONArray sessions, long fallback) {
        long max = fallback;
        for (int i = 0; i < sessions.length(); i++) max = Math.max(max, sessions.optJSONObject(i).optLong("to"));
        return max;
    }
}
