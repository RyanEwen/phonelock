package home.phonelock;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Watches the screens Google services opens. The lock must let Google services run (other apps borrow its
 * account picker, sign-in and location prompts), and Android can only allow or block a whole package, so
 * any of its screens could open inside the lock. Some lead out of it: "Manage your Google Account" (reached
 * from Calendar's profile picture), Google settings, Google help, feedback and Quick Share.
 *
 * <p>Each new screen is sorted by {@link #classify}:
 * <ul>
 *   <li>blocked: sent back at once, and the parents are told;</li>
 *   <li>known to be needed (or marked OK by a parent): left alone;</li>
 *   <li>anything else: left open, and the parents get a screenshot of it once, so they can mark it OK
 *       or blocked. The child is told the picture was sent, as with every screen view.</li>
 * </ul>
 * Parents' decisions are kept here (shared by both parents) and override the built-in lists. A decision is
 * about one screen (its class name) or a whole {@link Area}, and an allow can be for a while ("ok:<until ms>").
 * Nothing is watched in parent mode, which is when a parent adds accounts through these same screens.
 */
final class ScreenGuard {
    static final String GMS = "com.google.android.gms";

    enum Verdict { IGNORE, ALLOW, BLOCK, ALERT }

    /**
     * A group of screens that lead out of the lock, blocked unless a parent allows the group: {@code key}
     * names it in decisions ("area:" + key), {@code name} is what parents see, and {@code screens} are class
     * names after "com.google.android.gms" (one ending in "." matches everything under it). Found on a
     * current Pixel; screens not listed here are caught by the alerts instead.
     */
    static final class Area {
        final String key, name;
        final String[] screens;

        Area(String key, String name, String... screens) {
            this.key = key;
            this.name = name;
            this.screens = screens;
        }
    }

    static final Area[] AREAS = {
            new Area("account", "Manage your Google Account", ".accountsettings."),
            new Area("settings", "Google settings", ".app.settings.",
                    ".quickstart.xos.ui.XosGoogleSettingsActivity",
                    ".trustagent.activeunlock.primary.ui.activity.ActiveUnlockGoogleSettingsEntryPoint"),
            new Area("help", "Google help pages", ".googlehelp.", ".appfunctions.googlehelp."),
            new Area("feedback", "Send feedback to Google", ".feedback."),
            new Area("quickshare", "Quick Share (files from nearby phones)", ".nearby.sharing."),
    };

    /** Prefix of an area's key in the parents' decisions. */
    static final String AREA = "area:";

    /** Screens other apps rely on (account picker, sign-in, consent and location prompts): never reported. */
    static final String[] NEEDED = {
            ".common.account.",                   // Choose an account
            ".common.api.",                       // "Update Google Play services" and similar prompts
            ".auth.",                             // Sign-in, consent, SMS code consent
            ".signin.",
            ".identity.",
            ".fido.",                             // Passkeys and security keys
            ".location.settings.",                // "Turn on location accuracy"
            ".locationsharingreporter.",
            ".permission.",
    };

    /** How long a blocked screen's report waits before the same screen is reported again. */
    private static final long BLOCK_REPORT_EVERY_MS = 10 * 60_000L;
    /** An unknown screen is reported at most this often while no parent has decided about it. */
    private static final long ALERT_EVERY_MS = 24 * 3600_000L;
    /** Lets a new screen finish drawing before the screenshot. */
    private static final long SHOT_DELAY_MS = 1500;
    /** After "Back", how long before checking it worked (and going home if it didn't). */
    private static final long BACK_CHECK_MS = 700;
    private static final int NOTICE_ID = 0x5c4ee8;

    /** Created on first use, so the pure parts of this class load in plain unit tests. */
    private static Handler main;
    /** The last screen reported by Android, and the last app (not Google services) in front before it. */
    private static volatile String current = "";
    private static volatile String lastApp = "";

    private ScreenGuard() {}

    /**
     * Sorts screen {@code cls} of package {@code pkg} at time {@code now} by the parents' {@code rules}
     * (key -> "ok", "block" or "ok:<until ms>"; a screen's own decision before its area's) and the built-in
     * lists. Pure, for tests.
     */
    static Verdict classify(String pkg, String cls, Map<String, String> rules, long now) {
        if (!GMS.equals(pkg) || cls == null || !cls.startsWith(GMS + ".")) return Verdict.IGNORE;
        Verdict own = verdict(rules.get(cls), now);
        if (own != null) return own;
        String rest = cls.substring(GMS.length());
        Area area = areaOf(rest);
        if (area != null) {
            Verdict a = verdict(rules.get(AREA + area.key), now);
            return a != null ? a : Verdict.BLOCK;
        }
        if (matches(rest, NEEDED)) return Verdict.ALLOW;
        return Verdict.ALERT;
    }

    /** What decision {@code rule} means at {@code now}: null when there is none (or a time-limited allow ran out). */
    static Verdict verdict(String rule, long now) {
        if (rule == null) return null;
        if (rule.equals("ok")) return Verdict.ALLOW;
        if (rule.equals("block")) return Verdict.BLOCK;
        if (rule.startsWith("ok:")) {
            try {
                return now < Long.parseLong(rule.substring(3)) ? Verdict.ALLOW : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The area screen {@code rest} (class name after the package) belongs to, or null. */
    static Area areaOf(String rest) {
        for (Area a : AREAS) if (matches(rest, a.screens)) return a;
        return null;
    }

    private static Area areaByKey(String key) {
        for (Area a : AREAS) if (a.key.equals(key)) return a;
        return null;
    }

    private static boolean matches(String rest, String[] list) {
        for (String p : list) {
            if (p.endsWith(".") ? rest.startsWith(p) : rest.equals(p)) return true;
        }
        return false;
    }

    /** A short readable name for screen {@code cls}: "accountsettings › AccountDetails". */
    static String shortName(String cls) {
        String rest = cls.startsWith(GMS + ".") ? cls.substring(GMS.length() + 1) : cls;
        int dot = rest.lastIndexOf('.');
        String name = (dot < 0 ? rest : rest.substring(dot + 1)).replaceAll("Activity$", "");
        String area = dot < 0 ? "" : rest.substring(0, rest.indexOf('.') < 0 ? dot : rest.indexOf('.'));
        return area.isEmpty() ? name : area + " › " + name;
    }

    /**
     * A new window came to the front (from {@link ScreenService}). Never throws: this runs on every screen
     * change and a crash here would take the lock app down with it.
     */
    static void onWindow(AccessibilityService s, CharSequence pkgSeq, CharSequence clsSeq) {
        try {
            if (pkgSeq == null || clsSeq == null) return;
            String pkg = pkgSeq.toString(), cls = clsSeq.toString();
            if (!GMS.equals(pkg)) {
                // Remember where Google services screens are opened from, for the parents' report: apps' own
                // screens, not the system's overlays or plain dialogs and views ("android.widget...").
                if (!cls.startsWith("android.") && !pkg.equals("com.android.systemui")) lastApp = pkg;
                current = cls;
                return;
            }
            if (!cls.startsWith(GMS + ".")) return;  // a dialog or view inside a Google services screen
            current = cls;
            Context c = s.getApplicationContext();
            if (Policy.inParentMode(c)) return;

            switch (classify(pkg, cls, rules(c), System.currentTimeMillis())) {
                case BLOCK:
                    block(s, c, cls);
                    break;
                case ALERT:
                    alert(c, cls);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "screen guard: " + e);
        }
    }

    /** Leaves blocked screen {@code cls} with Back (so the app that opened it stays usable), else Home. */
    private static void block(AccessibilityService s, Context c, String cls) {
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        main().postDelayed(() -> {
            if (cls.equals(current)) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }, BACK_CHECK_MS);

        if (!due(c, "screen_block_" + cls, BLOCK_REPORT_EVERY_MS)) return;
        // Says which screen and area, so the parents' notification can offer to allow it for a while.
        try {
            Area area = areaOf(cls.substring(GMS.length()));
            String what = area != null ? area.name : "a Google services screen (" + shortName(cls) + ")";
            String text = "Blocked " + what + openedFrom(c);
            JSONObject o = Remote.msg("event");
            o.put("text", text);
            o.put("screen", cls);
            if (area != null) o.put("area", area.key);
            Remote.publish(c, o, text);
        } catch (Exception e) {
            Log.w(Policy.TAG, "screen block report: " + e);
        }
    }

    /** Sends the parents a screenshot of unknown screen {@code cls}, once it has drawn, if it's still up. */
    private static void alert(Context c, String cls) {
        if (!due(c, "screen_alert_" + cls, ALERT_EVERY_MS)) return;
        String from = lastApp;
        main().postDelayed(() -> {
            // The child may already have left it; the next visit reports it instead.
            if (!cls.equals(current)) {
                Policy.prefs(c).edit().remove("screen_alert_" + cls).apply();
                return;
            }
            ScreenService.snap(c, (jpeg, error) -> send(c, cls, from, jpeg, error));
        }, SHOT_DELAY_MS);
    }

    /** Publishes the alert for screen {@code cls}: the screenshot, or why there isn't one. */
    private static void send(Context c, String cls, String from, byte[] jpeg, String error) {
        try {
            JSONObject o = Remote.msg("seen");
            o.put("screen", cls);
            o.put("name", shortName(cls));
            if (!from.isEmpty()) o.put("from", Remote.label(c, from));
            if (error != null) o.put("error", error);
            if (jpeg == null) Remote.publish(c, o, null);
            else Remote.publishWithBlob(c, o, jpeg);
            if (jpeg != null) Remote.notifyKid(c, "A picture of this screen was sent to your parents", NOTICE_ID);
        } catch (Exception e) {
            Log.w(Policy.TAG, "screen alert: " + e);
        }
    }

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static String openedFrom(Context c) {
        String from = lastApp;
        return from.isEmpty() ? "" : ", opened from " + Remote.label(c, from);
    }

    /** True (and records now) if {@code key} wasn't recorded within {@code everyMs}. */
    private static boolean due(Context c, String key, long everyMs) {
        SharedPreferences p = Policy.prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong(key, 0) < everyMs) return false;
        p.edit().putLong(key, now).apply();
        return true;
    }

    // ---------- parents' decisions ----------

    /** The parents' decisions: screen class or "area:" key -> "ok", "block" or "ok:<until ms>". */
    static Map<String, String> rules(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(Policy.prefs(c).getString("screen_rules", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getString(k));
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "screen rules: " + e);
        }
        return out;
    }

    /**
     * Records a parent's decision about {@code key} (a screen class, or "area:" plus an area's key): "ok",
     * "block", or anything else to forget it. An "ok" with {@code untilMs} &gt; 0 lasts until then. Returns
     * whether the key was valid.
     */
    static boolean setRule(Context c, String key, String rule, long untilMs) {
        if (!validKey(key)) return false;
        Map<String, String> all = rules(c);
        if ("ok".equals(rule)) all.put(key, untilMs > 0 ? "ok:" + untilMs : "ok");
        else if ("block".equals(rule)) all.put(key, "block");
        else all.remove(key);
        SharedPreferences.Editor e = Policy.prefs(c).edit().putString("screen_rules", new JSONObject(all).toString());
        // A forgotten screen is reported again the next time it opens.
        e.remove("screen_alert_" + key).apply();
        return true;
    }

    static boolean validKey(String key) {
        if (key == null) return false;
        if (key.startsWith(AREA)) return areaByKey(key.substring(AREA.length())) != null;
        return key.startsWith(GMS + ".");
    }

    /** What parents call {@code key}: the area's name, or the screen's short name. */
    static String nameOf(String key) {
        if (key.startsWith(AREA)) {
            Area a = areaByKey(key.substring(AREA.length()));
            return a != null ? a.name : key;
        }
        return "the Google services screen " + shortName(key);
    }

    /** The decisions still in force, for the parents' state, so each parent can see and undo them. */
    static JSONObject rulesJson(Context c) {
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : rules(c).entrySet()) {
            if (verdict(e.getValue(), now) == null) continue; // a time-limited allow that ran out
            try {
                o.put(e.getKey(), e.getValue());
            } catch (Exception ignored) {}
        }
        return o;
    }

    /** The built-in areas, for the parents' state: [{key, name}], blocked unless a parent allows one. */
    static org.json.JSONArray areasJson() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Area area : AREAS) {
            try {
                a.put(new JSONObject().put("key", area.key).put("name", area.name));
            } catch (Exception ignored) {}
        }
        return a;
    }
}
