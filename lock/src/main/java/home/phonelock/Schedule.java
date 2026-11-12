package home.phonelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weekly schedule of situations ({@link #NORMAL}, {@link #LIMITED}, {@link #BEDTIME}) and which of the
 * scheduled apps are allowed in each. The scheduled apps are the home-screen list ({@link Policy#visibleApps});
 * each has a bit mask of situations it's allowed in. Temporary grants are separate and override this.
 *
 * Rules are {@code {mode, days, start, end}}: {@code days} is a bit mask with bit 0 = Monday … bit 6 = Sunday,
 * {@code start}/{@code end} are minutes after midnight; a rule with {@code end < start} runs past midnight
 * into the next day, and {@code end == start} covers the whole day. Bedtime wins where it overlaps limited hours.
 */
final class Schedule {
    static final int NORMAL = 1, LIMITED = 2, BEDTIME = 4;
    static final int ALL = NORMAL | LIMITED | BEDTIME;

    /** Mask for an app added to the home screen without a choice: everything but bedtime. */
    static final int DEFAULT_MASK = NORMAL | LIMITED;

    /** Apps also allowed at bedtime when the table is first created from the old home-screen list. */
    private static final String[] BEDTIME_DEFAULTS = {"com.android.dialer", "com.google.android.deskclock"};

    private Schedule() {}

    /** One time range of limited hours or bedtime. */
    static final class Rule {
        final int mode, days, start, end;

        Rule(int mode, int days, int start, int end) {
            this.mode = mode;
            this.days = days;
            this.start = start;
            this.end = end;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("mode", modeName(mode)).put("days", days).put("start", start).put("end", end);
        }

        /** Parses a rule, or returns null if it is malformed. */
        static Rule fromJson(JSONObject o) {
            int mode = parseMode(o.optString("mode"));
            int days = o.optInt("days") & 0x7f;
            int start = o.optInt("start", -1), end = o.optInt("end", -1);
            if ((mode != LIMITED && mode != BEDTIME) || days == 0) return null;
            if (start < 0 || start >= 1440 || end < 0 || end >= 1440) return null;
            return new Rule(mode, days, start, end);
        }

        /** True if this rule covers minute {@code minute} of weekday {@code day} (0 = Monday). */
        boolean covers(int day, int minute) {
            if (start == end) return has(day); // all day
            if (start < end) return has(day) && minute >= start && minute < end;
            // Overnight: the evening part on its own day, the morning part on the following day.
            return (has(day) && minute >= start) || (has((day + 6) % 7) && minute < end);
        }

        private boolean has(int day) {
            return (days & (1 << day)) != 0;
        }
    }

    // ---------- names ----------

    static String modeName(int mode) {
        if (mode == BEDTIME) return "bedtime";
        if (mode == LIMITED) return "limited";
        return "normal";
    }

    static int parseMode(String s) {
        if ("bedtime".equals(s)) return BEDTIME;
        if ("limited".equals(s)) return LIMITED;
        if ("normal".equals(s)) return NORMAL;
        return 0;
    }

    /** What the child sees, e.g. "Bedtime". */
    static String modeLabel(int mode) {
        if (mode == BEDTIME) return "Bedtime";
        if (mode == LIMITED) return "Limited hours";
        return "Normal";
    }

    // ---------- storage ----------

    static List<Rule> rules(Context c) {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Policy.prefs(c).getString("schedule", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Rule r = Rule.fromJson(a.getJSONObject(i));
                if (r != null) out.add(r);
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "schedule: " + e);
        }
        return out;
    }

    /** Replaces the schedule with the valid rules in {@code a}. */
    static void setRules(Context c, JSONArray a) throws Exception {
        JSONArray clean = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            Rule r = Rule.fromJson(a.getJSONObject(i));
            if (r != null) clean.put(r.toJson());
        }
        Policy.prefs(c).edit().putString("schedule", clean.toString()).apply();
    }

    static JSONArray rulesJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Rule r : rules(c)) a.put(r.toJson());
        return a;
    }

    /**
     * Situation mask per scheduled app, in home-screen order. Created on first use from the home-screen list
     * (normal and limited for all, bedtime for Phone and Clock).
     */
    static synchronized Map<String, Integer> masks(Context c) {
        SharedPreferences p = Policy.prefs(c);
        Map<String, Integer> out = new LinkedHashMap<>();
        JSONObject stored = null;
        try {
            String s = p.getString("app_masks", null);
            if (s != null) stored = new JSONObject(s);
        } catch (Exception ignored) {}
        for (String pkg : Policy.visibleApps(c)) {
            int m = stored != null ? stored.optInt(pkg, DEFAULT_MASK) : defaultMask(pkg);
            out.put(pkg, m & ALL);
        }
        if (stored == null) saveMasks(c, out);
        return out;
    }

    private static int defaultMask(String pkg) {
        for (String b : BEDTIME_DEFAULTS) if (b.equals(pkg)) return ALL;
        return DEFAULT_MASK;
    }

    private static void saveMasks(Context c, Map<String, Integer> masks) {
        Policy.prefs(c).edit().putString("app_masks", new JSONObject(masks).toString()).apply();
    }

    /**
     * Sets the situations {@code pkg} is allowed in. A non-zero mask puts it on the home-screen list (at the
     * end if new); zero takes it off, so it is only reachable through a temporary grant.
     */
    static synchronized void setMask(Context c, String pkg, int mask) {
        mask &= ALL;
        Map<String, Integer> masks = masks(c);
        List<String> apps = new ArrayList<>(Policy.visibleApps(c));
        if (mask == 0) {
            apps.remove(pkg);
            masks.remove(pkg);
        } else {
            if (!apps.contains(pkg)) apps.add(pkg);
            masks.put(pkg, mask);
        }
        Policy.prefs(c).edit().putString("apps", String.join(",", apps)).apply();
        saveMasks(c, masks);
    }

    // ---------- evaluation ----------

    /** The situation at {@code cal}'s time. */
    static int modeAt(List<Rule> rules, Calendar cal) {
        int day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Calendar: Sunday = 1 -> 6, Monday = 2 -> 0
        int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        boolean limited = false;
        for (Rule r : rules) {
            if (!r.covers(day, minute)) continue;
            if (r.mode == BEDTIME) return BEDTIME;
            limited = true;
        }
        return limited ? LIMITED : NORMAL;
    }

    // ---------- manual situation ----------

    /** Manually started situation ({@link #LIMITED} or {@link #BEDTIME}) still running, else 0. */
    static int overrideMode(Context c) {
        return System.currentTimeMillis() < overrideUntil(c) ? Policy.prefs(c).getInt("override_mode", 0) : 0;
    }

    static long overrideUntil(Context c) {
        return Policy.prefs(c).getLong("override_until", 0);
    }

    /**
     * Starts limited hours or bedtime by hand until {@code until} (ms); any other mode ends it. It can only add
     * restrictions: where the schedule is stricter (bedtime starting during manual limited hours) the
     * schedule wins, and when it ends the schedule applies again.
     */
    static void setOverride(Context c, int mode, long until) {
        if (mode != LIMITED && mode != BEDTIME) {
            mode = 0;
            until = 0;
        }
        Policy.prefs(c).edit().putInt("override_mode", mode).putLong("override_until", until).commit();
    }

    /** The stricter of two situations: bedtime over limited hours over normal (the bit values are ordered). */
    private static int stricter(int a, int b) {
        return Math.max(a, b);
    }

    /** The situation at {@code ms}: the schedule, made stricter by a manual situation while it runs. */
    private static int modeAt(List<Rule> rules, int manual, long manualUntil, long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        int m = modeAt(rules, cal);
        return ms < manualUntil ? stricter(m, manual) : m;
    }

    static int currentMode(Context c) {
        return stricter(modeAt(rules(c), Calendar.getInstance()), overrideMode(c));
    }

    /**
     * When the current situation next changes, in ms since the epoch, or 0 if it never does. Checks each
     * minute of the coming week, plus the exact moment a manual situation ends.
     */
    static long currentModeUntil(Context c) {
        List<Rule> rules = rules(c);
        int manual = overrideMode(c);
        long manualUntil = manual == 0 ? 0 : overrideUntil(c);
        if (rules.isEmpty() && manual == 0) return 0;
        long now = System.currentTimeMillis();
        int current = modeAt(rules, manual, manualUntil, now);
        long t = now - now % 60_000;
        for (int i = 0; i < 8 * 1440; i++) {
            long next = t + 60_000;
            if (manualUntil > t && manualUntil <= next && modeAt(rules, manual, manualUntil, manualUntil) != current) {
                return manualUntil;
            }
            if (modeAt(rules, manual, manualUntil, next) != current) return next;
            t = next;
        }
        return 0;
    }

    /**
     * Scheduled apps allowed in the current situation, in home-screen order, minus any a parent has blocked
     * for a while ({@link Grants#loadBlocks}) and any whose daily time limit has run out ({@link AppTime}).
     */
    static List<String> allowedNow(Context c) {
        int mode = currentMode(c);
        java.util.Set<String> blocked = new java.util.HashSet<>(Grants.loadBlocks(c).keySet());
        blocked.addAll(AppTime.blockedApps(c));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : masks(c).entrySet()) {
            if ((e.getValue() & mode) != 0 && !blocked.contains(e.getKey())) out.add(e.getKey());
        }
        return out;
    }
}
