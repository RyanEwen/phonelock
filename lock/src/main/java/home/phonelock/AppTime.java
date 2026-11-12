package home.phonelock;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Daily app time limits and the record of time spent in each app.
 *
 * <p><b>Limits.</b> A parent sets a limit on one app, or on a named group of apps that share one allowance
 * ("Games: 1 h"), with minutes for each day of the week. Time counts while an app is on screen
 * ({@link AppUsage#intervals}), except while a parent has allowed that app for a while (a grant is extra time
 * on top of the limit) and during parent mode. Five minutes before a limit runs out the child is warned;
 * when it runs out its apps leave the home screen like any other app that isn't allowed
 * ({@link Schedule#allowedNow} skips them), until midnight or until a parent allows them for a while.
 * The schedule still decides whether an app can be used at all; a limit only caps how long.
 *
 * <p><b>Record.</b> Each day's minutes per app are kept here for a year (Android keeps its own detail for
 * only about ten days), so parents' reports can ask for any range ({@code usage} message).
 *
 * <p>Checked on the service's timer ({@link #tick}), so a limit can run over by up to that tick (20 s).
 */
final class AppTime {
    /** Warn the child this long before a limit runs out. */
    private static final long WARN_MS = 5 * 60_000L;
    /** While the screen is off nothing is used, so measure only this often (to notice a new day). */
    private static final long IDLE_MEASURE_MS = 10 * 60_000L;
    /** How often today's totals are written to the record. */
    private static final long SAVE_EVERY_MS = 5 * 60_000L;
    private static final int KEEP_DAYS = 400;
    /** Grants and parent-mode stretches older than this aren't needed to measure today. */
    private static final long EXCUSE_KEEP_MS = 48 * 3600_000L;
    /** In the excused-time log, a stretch that applies to every app (parent mode). */
    private static final String ALL_APPS = "*";

    /** Today's measurement, as last taken: totals per app and counted minutes per limit. */
    private static volatile JSONObject today;
    private static long lastMeasured, lastSaved;

    private AppTime() {}

    // ---------- limits ----------

    /** One limit: its apps share {@code minutes[day]} a day (day 0 = Sunday; -1 = no limit that day). */
    static final class Limit {
        final String id;
        final String name;
        final List<String> apps;
        final int[] minutes;

        Limit(String id, String name, List<String> apps, int[] minutes) {
            this.id = id;
            this.name = name;
            this.apps = apps;
            this.minutes = minutes;
        }

        /** What the child and parents call it: the group's name, or the app's. */
        String title(Context c) {
            if (name != null && !name.isEmpty()) return name;
            return apps.isEmpty() ? "Apps" : Remote.label(c, apps.get(0));
        }

        /** Minutes allowed on the day of {@code cal}, or -1 for no limit that day. */
        int minutesOn(Calendar cal) {
            return minutes[cal.get(Calendar.DAY_OF_WEEK) - 1];
        }

        static Limit fromJson(JSONObject o) {
            List<String> apps = new ArrayList<>();
            JSONArray a = o.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                String p = a.optString(i);
                if (!p.isEmpty() && !apps.contains(p)) apps.add(p);
            }
            int[] min = new int[7];
            JSONArray m = o.optJSONArray("min");
            for (int i = 0; i < 7; i++) {
                int v = m == null ? -1 : m.optInt(i, -1);
                min[i] = v < 0 ? -1 : Math.min(v, 24 * 60);
            }
            return new Limit(o.optString("id"), o.optString("name"), apps, min);
        }

        JSONObject toJson() throws Exception {
            JSONArray m = new JSONArray();
            for (int v : minutes) m.put(v);
            return new JSONObject().put("id", id).put("name", name).put("apps", new JSONArray(apps)).put("min", m);
        }
    }

    static List<Limit> limits(Context c) {
        List<Limit> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Policy.prefs(c).getString("time_limits", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Limit l = Limit.fromJson(a.getJSONObject(i));
                if (!l.id.isEmpty() && !l.apps.isEmpty()) out.add(l);
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "time limits: " + e);
        }
        return out;
    }

    /** Replaces every limit (a parent's edit sends the whole list), then re-checks them at once. */
    static void setLimits(Context c, JSONArray a) throws Exception {
        JSONArray clean = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            Limit l = Limit.fromJson(a.getJSONObject(i));
            if (!l.id.isEmpty() && !l.apps.isEmpty()) clean.put(l.toJson());
        }
        Policy.prefs(c).edit().putString("time_limits", clean.toString()).apply();
        tick(c, true);
    }

    static JSONArray limitsJson(Context c) {
        JSONArray a = new JSONArray();
        try {
            for (Limit l : limits(c)) a.put(l.toJson());
        } catch (Exception ignored) {}
        return a;
    }

    /** Apps whose limit has run out today; {@link Schedule#allowedNow} leaves them out. Cheap: no measuring. */
    static Set<String> blockedApps(Context c) {
        Set<String> out = new HashSet<>();
        Set<String> ids = dayIds(c, "time_out");
        if (ids.isEmpty()) return out;
        for (Limit l : limits(c)) if (ids.contains(l.id)) out.addAll(l.apps);
        return out;
    }

    // ---------- time that doesn't count ----------

    /**
     * Records the stretches during which apps were allowed by a grant, so that time isn't counted against
     * their limits. Called with the grants before and after every change ({@link Grants#save}).
     */
    static synchronized void noteGrants(Context c, Map<String, Long> before, Map<String, Long> after) {
        long now = System.currentTimeMillis();
        JSONArray log = excuses(c);
        try {
            for (Map.Entry<String, Long> e : after.entrySet()) {
                Long old = before.get(e.getKey());
                if (old != null && old.equals(e.getValue())) continue;
                // A new or changed grant: extend the open stretch, or start one.
                JSONObject open = openExcuse(log, e.getKey(), now);
                if (open != null) open.put("to", e.getValue());
                else log.put(new JSONObject().put("app", e.getKey()).put("from", now).put("to", e.getValue()));
            }
            for (String p : before.keySet()) {
                if (after.containsKey(p)) continue;
                // Ended early (revoked): its stretch stops now.
                JSONObject open = openExcuse(log, p, now);
                if (open != null) open.put("to", now);
            }
        } catch (Exception ignored) {}
        saveExcuses(c, log, now);
    }

    /** Records parent mode starting (until {@code untilMs}) or ending (0), so that time isn't counted. */
    static synchronized void noteParentMode(Context c, long untilMs) {
        long now = System.currentTimeMillis();
        JSONArray log = excuses(c);
        try {
            JSONObject open = openExcuse(log, ALL_APPS, now);
            if (untilMs > now) {
                if (open != null) open.put("to", untilMs);
                else log.put(new JSONObject().put("app", ALL_APPS).put("from", now).put("to", untilMs));
            } else if (open != null) {
                open.put("to", now);
            }
        } catch (Exception ignored) {}
        saveExcuses(c, log, now);
    }

    private static JSONArray excuses(Context c) {
        try {
            return new JSONArray(Policy.prefs(c).getString("time_excused", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject openExcuse(JSONArray log, String app, long now) {
        for (int i = log.length() - 1; i >= 0; i--) {
            JSONObject o = log.optJSONObject(i);
            if (o != null && app.equals(o.optString("app")) && o.optLong("to") > now) return o;
        }
        return null;
    }

    private static void saveExcuses(Context c, JSONArray log, long now) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o != null && o.optLong("to") > now - EXCUSE_KEEP_MS) kept.put(o);
        }
        Policy.prefs(c).edit().putString("time_excused", kept.toString()).apply();
    }

    // ---------- measuring ----------

    /**
     * Measures today and acts on it: warns, blocks apps whose limit ran out, unblocks ones whose limit was
     * raised, and starts afresh at midnight. Runs on the service's timer; {@code force} measures even while
     * the screen is off (a parent changed the limits).
     */
    static synchronized void tick(Context c, boolean force) {
        long now = System.currentTimeMillis();
        boolean newDay = rollOver(c, now);
        PowerManager pm = c.getSystemService(PowerManager.class);
        boolean screenOn = pm == null || pm.isInteractive();
        if (!force && !newDay && !screenOn && now - lastMeasured < IDLE_MEASURE_MS) return;
        lastMeasured = now;

        Calendar cal = Calendar.getInstance();
        long midnight = startOfDay(now);
        Map<String, List<long[]>> used = AppUsage.intervals(c, midnight, now);
        Map<String, Long> totals = totals(used);
        Map<String, Long> counted = counted(c, used);
        JSONObject t = new JSONObject();
        try {
            t.put("day", dayKey(now));
            t.put("apps", minutesJson(totals));
            JSONObject perLimit = new JSONObject();
            for (Limit l : limits(c)) perLimit.put(l.id, sum(counted, l.apps) / 60_000L);
            t.put("used", perLimit);
        } catch (Exception ignored) {}
        today = t;

        if (now - lastSaved >= SAVE_EVERY_MS || newDay || force) {
            lastSaved = now;
            saveDay(c, dayKey(now), totals);
        }
        enforce(c, cal, counted, newDay);
    }

    /** Warns and blocks for each limit; re-applies the policy when the set of blocked apps changed. */
    private static void enforce(Context c, Calendar cal, Map<String, Long> counted, boolean newDay) {
        Set<String> out = dayIds(c, "time_out");
        Set<String> warned = dayIds(c, "time_warned");
        Set<String> nowOut = new LinkedHashSet<>();
        List<String> ranOut = new ArrayList<>();
        for (Limit l : limits(c)) {
            int allowed = l.minutesOn(cal);
            if (allowed < 0) continue;
            long left = allowed * 60_000L - sum(counted, l.apps);
            if (left <= 0) {
                nowOut.add(l.id);
                if (!out.contains(l.id)) ranOut.add(l.title(c));
            } else if (left <= WARN_MS && !warned.contains(l.id) && allowed > 5) {
                warned.add(l.id);
                Remote.notifyKid(c, minutesText(left) + " left today for " + l.title(c));
            }
        }
        saveDayIds(c, "time_warned", warned);
        if (nowOut.equals(out) && !newDay) return;
        saveDayIds(c, "time_out", nowOut);
        Policy.applySoon(c);
        if (!ranOut.isEmpty()) {
            String names = String.join(", ", ranOut);
            Remote.notifyKid(c, "Time's up for today: " + names + ". Ask a parent if you need more time.");
            if (Remote.paired(c)) Remote.event(c, "Time's up for today: " + names, null, true);
        }
        if (Remote.paired(c)) Remote.sendState(c, false);
    }

    /**
     * At the first tick of a new day: finishes yesterday's record and clears yesterday's blocks and warnings.
     * Also fills in the days Android still remembers the first time this runs. Returns true on a new day.
     */
    private static boolean rollOver(Context c, long now) {
        android.content.SharedPreferences p = Policy.prefs(c);
        String key = dayKey(now);
        String last = p.getString("time_day", "");
        if (key.equals(last)) return false;
        p.edit().putString("time_day", key).apply();
        long midnight = startOfDay(now);
        if (!p.getBoolean("time_backfilled", false)) {
            // Android keeps its detailed record for about ten days: keep what it still has.
            for (int d = 9; d >= 1; d--) recordDay(c, midnight, d);
            p.edit().putBoolean("time_backfilled", true).apply();
        } else if (!last.isEmpty()) {
            recordDay(c, midnight, 1);
        }
        return true;
    }

    /** Measures the whole day {@code daysAgo} days before {@code midnight}'s and stores it. */
    private static void recordDay(Context c, long midnight, int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(midnight);
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        long start = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        saveDay(c, dayKey(start), totals(AppUsage.intervals(c, start, cal.getTimeInMillis())));
    }

    private static Map<String, Long> totals(Map<String, List<long[]>> used) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, List<long[]>> e : used.entrySet()) {
            long ms = 0;
            for (long[] iv : e.getValue()) ms += iv[1] - iv[0];
            out.put(e.getKey(), ms);
        }
        return out;
    }

    /** Like {@link #totals}, minus the time each app was allowed by a grant or used in parent mode. */
    private static Map<String, Long> counted(Context c, Map<String, List<long[]>> used) {
        JSONArray log = excuses(c);
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, List<long[]>> e : used.entrySet()) {
            List<long[]> excused = new ArrayList<>();
            for (int i = 0; i < log.length(); i++) {
                JSONObject o = log.optJSONObject(i);
                if (o == null) continue;
                String app = o.optString("app");
                if (app.equals(e.getKey()) || app.equals(ALL_APPS)) excused.add(new long[]{o.optLong("from"), o.optLong("to")});
            }
            long ms = 0;
            for (long[] iv : e.getValue()) ms += iv[1] - iv[0] - overlap(iv, merge(excused));
            out.put(e.getKey(), Math.max(0, ms));
        }
        return out;
    }

    /** Sorts and joins overlapping stretches, so overlaps aren't subtracted twice. */
    private static List<long[]> merge(List<long[]> in) {
        in.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] iv : in) {
            if (!out.isEmpty() && iv[0] <= out.get(out.size() - 1)[1]) {
                long[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                out.add(new long[]{iv[0], iv[1]});
            }
        }
        return out;
    }

    private static long overlap(long[] iv, List<long[]> merged) {
        long ms = 0;
        for (long[] m : merged) ms += Math.max(0, Math.min(iv[1], m[1]) - Math.max(iv[0], m[0]));
        return ms;
    }

    private static long sum(Map<String, Long> ms, List<String> apps) {
        long t = 0;
        for (String p : apps) {
            Long v = ms.get(p);
            if (v != null) t += v;
        }
        return t;
    }

    // ---------- the record ----------

    private static File recordFile(Context c) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "app_days.json");
    }

    /** Every recorded day: "yyyy-MM-dd" -> {package: minutes}. */
    private static synchronized JSONObject loadDays(Context c) {
        try {
            File f = recordFile(c);
            if (f.isFile()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(Policy.TAG, "app days: " + e);
        }
        return new JSONObject();
    }

    /** Stores one day's totals (apps used under a minute are left out) and drops days older than a year. */
    private static synchronized void saveDay(Context c, String day, Map<String, Long> totals) {
        JSONObject days = loadDays(c);
        try {
            days.put(day, minutesJson(totals));
            String oldest = dayKey(System.currentTimeMillis() - KEEP_DAYS * 24 * 3600_000L);
            List<String> drop = new ArrayList<>();
            for (java.util.Iterator<String> it = days.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.compareTo(oldest) < 0) drop.add(k);
            }
            for (String k : drop) days.remove(k);
            // Written whole to a temporary file, then moved over the old one, so a crash can't leave half a file.
            File f = recordFile(c);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(days.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) Log.w(Policy.TAG, "app days: couldn't save");
        } catch (Exception e) {
            Log.w(Policy.TAG, "app days: " + e);
        }
    }

    private static JSONObject minutesJson(Map<String, Long> ms) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Long> e : ms.entrySet()) {
            long min = e.getValue() / 60_000L;
            if (min > 0) o.put(e.getKey(), min);
        }
        return o;
    }

    /**
     * Answers a parent's report request: every recorded day from {@code since} ("yyyy-MM-dd") on, today
     * included as measured now, with the apps' names. Off the main thread.
     */
    static void sendHistory(Context c, String since, String req) {
        new Thread(() -> {
            try {
                tick(c, true);
                JSONObject all = loadDays(c);
                JSONObject t = today;
                if (t != null) all.put(t.optString("day"), t.optJSONObject("apps"));
                JSONObject days = new JSONObject();
                JSONObject labels = new JSONObject();
                for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (since != null && !since.isEmpty() && k.compareTo(since) < 0) continue;
                    JSONObject apps = all.optJSONObject(k);
                    days.put(k, apps);
                    for (java.util.Iterator<String> a = apps.keys(); a.hasNext(); ) {
                        String p = a.next();
                        if (!labels.has(p)) labels.put(p, Remote.label(c, p));
                    }
                }
                JSONObject o = Remote.msg("usage").put("days", days).put("labels", labels);
                if (req != null && !req.isEmpty()) o.put("req", req);
                if (!AppUsage.permitted(c)) o.put("error", "The device hasn't allowed reading app use");
                Remote.publish(c, o, null);
            } catch (Exception e) {
                Log.w(Policy.TAG, "usage history: " + e);
            }
        }, "usage").start();
    }

    /** Adds the limits and today's use (as last measured) to a state message. */
    static void putState(Context c, JSONObject o) throws Exception {
        o.put("limits", limitsJson(c));
        JSONObject t = today;
        if (t != null && dayKey(System.currentTimeMillis()).equals(t.optString("day"))) {
            JSONObject copy = new JSONObject(t.toString());
            JSONObject labels = new JSONObject();
            JSONObject apps = copy.optJSONObject("apps");
            for (java.util.Iterator<String> a = apps.keys(); a.hasNext(); ) {
                String p = a.next();
                labels.put(p, Remote.label(c, p));
            }
            copy.put("labels", labels);
            copy.put("out", new JSONArray(dayIds(c, "time_out")));
            o.put("appTime", copy);
        }
    }

    // ---------- helpers ----------

    /** A set of limit ids stored for today only ("day|id,id"); empty if stored on another day. */
    private static Set<String> dayIds(Context c, String key) {
        Set<String> out = new LinkedHashSet<>();
        String v = Policy.prefs(c).getString(key, "");
        int bar = v.indexOf('|');
        if (bar < 0 || !v.substring(0, bar).equals(dayKey(System.currentTimeMillis()))) return out;
        for (String id : v.substring(bar + 1).split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    private static void saveDayIds(Context c, String key, Set<String> ids) {
        Policy.prefs(c).edit().putString(key, dayKey(System.currentTimeMillis()) + "|" + String.join(",", ids)).apply();
    }

    static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(ms));
    }

    private static long startOfDay(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static String minutesText(long ms) {
        long m = Math.max(1, (ms + 59_999) / 60_000);
        return m == 1 ? "1 minute" : m + " minutes";
    }
}
