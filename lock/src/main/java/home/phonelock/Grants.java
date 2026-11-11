package home.phonelock;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Temporary app access: package -> expiry time (ms). */
public final class Grants {
    private Grants() {}

    static final Map<String, String[]> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("whatsapp", new String[]{"com.whatsapp"});
        ALIASES.put("messages", new String[]{"com.google.android.apps.messaging"});
        ALIASES.put("sms", new String[]{"com.google.android.apps.messaging"});
        ALIASES.put("texting", new String[]{"com.google.android.apps.messaging"});
        ALIASES.put("maps", new String[]{"com.google.android.apps.maps"});
        ALIASES.put("gmail", new String[]{"com.google.android.gm"});
        ALIASES.put("chat", new String[]{"com.google.android.apps.dynamite"});
    }
    static final String SMS_APP = "com.google.android.apps.messaging";

    /** Live temporary grants: package -> end time (ms). */
    static Map<String, Long> load(Context c) {
        return loadTimed(c, "grants");
    }

    static void save(Context c, Map<String, Long> m) {
        saveTimed(c, "grants", m);
    }

    /** Live temporary blocks of normally allowed apps: package -> end time (ms). */
    static Map<String, Long> loadBlocks(Context c) {
        return loadTimed(c, "blocks");
    }

    static void saveBlocks(Context c, Map<String, Long> m) {
        saveTimed(c, "blocks", m);
    }

    /** Reads a "pkg=untilMs,…" preference, dropping entries that have ended. */
    private static Map<String, Long> loadTimed(Context c, String key) {
        Map<String, Long> m = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String e : Policy.prefs(c).getString(key, "").split(",")) {
            int i = e.indexOf('=');
            if (i <= 0) continue;
            try {
                long t = Long.parseLong(e.substring(i + 1));
                if (t > now) m.put(e.substring(0, i), t);
            } catch (NumberFormatException ignored) {}
        }
        return m;
    }

    private static void saveTimed(Context c, String key, Map<String, Long> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Policy.prefs(c).edit().putString(key, sb.toString()).apply();
    }

    /** Earliest expiry, or 0 if none. */
    static long nextExpiry(Context c) {
        long n = 0;
        for (long t : load(c).values()) if (n == 0 || t < n) n = t;
        return n;
    }

    static String[] resolve(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        if (ALIASES.containsKey(k)) return ALIASES.get(k);
        if (name.contains(".")) return new String[]{name};
        return null;
    }

    private static final Pattern DUR = Pattern.compile("(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m)?");

    /** "2h", "90m", "1h30m", "45" (minutes). Returns minutes or -1. */
    static long parseMinutes(String s) {
        s = s.toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.matches("\\d+")) return Long.parseLong(s);
        Matcher m = DUR.matcher(s);
        if (!m.matches() || (m.group(1) == null && m.group(2) == null)) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long mi = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        return h * 60 + mi;
    }

    /** Handle one remote/adb command. Returns a human reply. */
    static String command(Context c, String text) {
        String[] w = text.trim().split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) return null;
        String verb = w[0].toLowerCase(Locale.ROOT);
        Map<String, Long> g = load(c);
        switch (verb) {
            case "allow":
            case "give":
            case "grant": {
                if (w.length < 2) return "Usage: allow whatsapp 2h";
                String[] pkgs = resolve(w[1]);
                if (pkgs == null) return "Unknown app '" + w[1] + "'. Known: " + ALIASES.keySet();
                long mins = w.length >= 3 ? parseMinutes(join(w, 2)) : 60;
                if (mins <= 0) return "Couldn't read the time. Try 2h, 90m or 1h30m.";
                mins = Math.min(mins, 24 * 60);
                long until = System.currentTimeMillis() + mins * 60_000L;
                for (String p : pkgs) {
                    if (!Policy.isPresent(c, p)) return p + " is not installed";
                    g.put(p, until);
                }
                save(c, g);
                Policy.apply(c);
                return w[1] + " allowed until " + time(c, until);
            }
            case "revoke":
            case "remove":
            case "take":
            case "stop": {
                if (w.length < 2 || w[1].equalsIgnoreCase("all")) {
                    g.clear();
                } else {
                    String[] pkgs = resolve(w[1]);
                    if (pkgs == null) return "Unknown app '" + w[1] + "'";
                    for (String p : pkgs) g.remove(p);
                }
                save(c, g);
                Policy.apply(c);
                return "Revoked. " + describe(c, load(c));
            }
            case "status":
            case "?":
                return describe(c, g);
            case "help":
                return "Commands: allow whatsapp 2h | revoke whatsapp | revoke all | status. Apps: "
                        + ALIASES.keySet();
            default:
                return "Unknown command. Send 'help'.";
        }
    }

    static String describe(Context c, Map<String, Long> g) {
        if (g.isEmpty()) return "No extra apps allowed.";
        StringBuilder sb = new StringBuilder("Allowed: ");
        for (Map.Entry<String, Long> e : g.entrySet()) {
            sb.append(label(e.getKey())).append(" until ").append(time(c, e.getValue())).append("; ");
        }
        return sb.toString().trim();
    }

    private static String label(String pkg) {
        for (Map.Entry<String, String[]> e : ALIASES.entrySet())
            if (e.getValue()[0].equals(pkg)) return e.getKey();
        return pkg;
    }

    private static String time(Context c, long t) {
        return android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(t));
    }

    private static String join(String[] w, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < w.length; i++) sb.append(w[i]);
        return sb.toString();
    }
}
