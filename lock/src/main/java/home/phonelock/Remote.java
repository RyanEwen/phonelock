package home.phonelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Encrypted parent <-> phone protocol over ntfy. See PROTOCOL.md. */
public final class Remote {
    static final String FROM = "phone";
    /** Longest sealed body sent inline; ntfy's message limit is 4096 bytes. */
    private static final int MAX_INLINE = 3800;
    private static final long MAX_AGE_MS = 6 * 3600_000L;
    /** Longest a grant or block can last, and so the longest it can be worth applying late. */
    private static final long LONG_MAX_AGE_MS = 24 * 3600_000L;
    private static final long MAX_FUTURE_MS = 5 * 60_000L;
    private Remote() {}

    static String secret(Context c) {
        return Policy.prefs(c).getString("secret", "");
    }

    static boolean paired(Context c) {
        return !secret(c).isEmpty() && !RemoteService.topic(c).isEmpty();
    }

    // ---------- incoming ----------

    /** Handle one ntfy message body. */
    static void handle(Context c, String body) {
        handle(c, body, null);
    }

    /** As {@link #handle(Context, String)}, with the ntfy attachment URL when the message carried a file. */
    static void handle(Context c, String body, String attachmentUrl) {
        if (!paired(c)) {
            // Legacy plain-text commands only work until a pairing secret is set.
            if (body.startsWith(RemoteService.REPLY_PREFIX) || body.startsWith("{")) return;
            String out = Grants.command(c, body);
            if (out != null) RemoteService.replyPlain(c, out);
            return;
        }
        String json = Crypto.open(Crypto.key(secret(c)), body);
        if (json == null) return; // not ours, or forged
        try {
            JSONObject m = new JSONObject(json);
            String from = m.optString("from");
            if (FROM.equals(from)) return; // our own echo
            if (!fresh(c, m)) return;
            if ("big".equals(m.optString("t"))) {
                // A parent message too large for ntfy's 4 KB limit, carried as an encrypted attachment.
                String inner = fetchBig(c, attachmentUrl, m.optString("id"));
                JSONObject im = inner == null ? null : new JSONObject(inner);
                if (im == null || FROM.equals(im.optString("from")) || !fresh(c, im)) return;
                dispatch(c, im, im.optString("from"), null);
                return;
            }
            dispatch(c, m, from, attachmentUrl);
        } catch (Exception e) {
            Log.w(Policy.TAG, "remote: " + e);
        }
    }

    /** Downloads and decrypts a "big" wrapper's attachment (at most 1 MB); null if anything is off. */
    private static String fetchBig(Context c, String url, String msgId) {
        if (url == null || !url.startsWith(RemoteService.server(c) + "/")) return null;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            byte[] enc;
            try (java.io.InputStream in = h.getInputStream()) {
                enc = in.readNBytes(1 << 20);
            } finally {
                h.disconnect();
            }
            byte[] plain = Crypto.openBytes(Crypto.key(secret(c)), enc, "phonelock/blob/" + msgId);
            return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(Policy.TAG, "big: " + e);
            return null;
        }
    }

    /**
     * Drops messages outside the freshness window and ones already handled. Seen ids are kept with their
     * timestamps for the whole window (not just the last N), so a captured message can't be replayed later.
     */
    private static synchronized boolean fresh(Context c, JSONObject m) {
        long ts = m.optLong("ts"), now = System.currentTimeMillis();
        // Grants and blocks stay meaningful while their time lasts (up to 24 h), so a device that was off
        // overnight still applies the rest of a long one; everything else expires after 6 h.
        String t = m.optString("t");
        long maxAge = ("grant".equals(t) || "block".equals(t) || "mode".equals(t))
                ? Math.min(LONG_MAX_AGE_MS, m.optLong("minutes", 0) * 60_000L) : MAX_AGE_MS;
        maxAge = Math.max(maxAge, MAX_AGE_MS);
        if (ts <= 0 || now - ts > maxAge || ts - now > MAX_FUTURE_MS) return false;
        String id = m.optString("id");
        if (id.isEmpty() || id.contains(",") || id.contains(":")) return false;
        SharedPreferences p = Policy.prefs(c);
        List<String> kept = new ArrayList<>();
        for (String e : p.getString("seen_ids", "").split(",")) {
            int colon = e.lastIndexOf(':');
            if (colon <= 0) continue; // old format without a timestamp: drop
            long seenTs;
            try { seenTs = Long.parseLong(e.substring(colon + 1)); } catch (NumberFormatException x) { continue; }
            if (now - seenTs > LONG_MAX_AGE_MS + MAX_FUTURE_MS) continue;
            if (e.substring(0, colon).equals(id)) return false;
            kept.add(e);
        }
        kept.add(id + ":" + ts);
        while (kept.size() > 5000) kept.remove(0); // safety cap, far above normal traffic
        p.edit().putString("seen_ids", String.join(",", kept)).apply();
        return true;
    }

    /** Commands that only make sense right away (ringing, locating, screenshots) are dropped after this. */
    private static final long LIVE_MAX_AGE_MS = 5 * 60_000L;
    /** Deleting or renaming files and removing contacts are refused if they arrive later than this. */
    private static final long DESTRUCTIVE_MAX_AGE_MS = 30 * 60_000L;

    private static boolean recent(JSONObject m) {
        return System.currentTimeMillis() - m.optLong("ts") <= DESTRUCTIVE_MAX_AGE_MS;
    }

    private static boolean live(JSONObject m) {
        return System.currentTimeMillis() - m.optLong("ts") <= LIVE_MAX_AGE_MS;
    }

    private static void dispatch(Context c, JSONObject m, String from, String attachmentUrl) throws Exception {
        String who = from.isEmpty() ? "A parent" : from;
        switch (m.optString("t")) {
            case "status":
                sendState(c, m.optBoolean("full", false));
                break;
            case "grant": {
                String pkg = m.optString("app");
                long mins = Math.max(1, Math.min(24 * 60, m.optLong("minutes", 60)));
                if (!Policy.isPresent(c, pkg)) {
                    event(c, label(c, pkg) + " is not installed", null, false);
                    break;
                }
                // Count from when the parent sent it, so a grant can't be saved up by keeping the phone
                // offline and spent later (e.g. at bedtime, which grants override).
                long now = System.currentTimeMillis();
                long until = Math.min(now, m.optLong("ts", now)) + mins * 60_000L;
                if (until <= now) {
                    event(c, label(c, pkg) + " was allowed while the device was offline; that time has passed");
                    break;
                }
                Map<String, Long> g = Grants.load(c);
                g.put(pkg, until);
                Grants.save(c, g);
                // The latest decision wins: allowing an app ends any block on it.
                Map<String, Long> b = Grants.loadBlocks(c);
                if (b.remove(pkg) != null) Grants.saveBlocks(c, b);
                Policy.apply(c);
                String txt = label(c, pkg) + " allowed until " + time(c, until);
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt, who, false);
                sendState(c, false);
                break;
            }
            case "revoke": {
                String pkg = m.optString("app");
                Map<String, Long> g = Grants.load(c);
                if ("all".equals(pkg)) g.clear(); else g.remove(pkg);
                Grants.save(c, g);
                Policy.apply(c);
                String txt = ("all".equals(pkg) ? "All extra apps" : label(c, pkg)) + " turned off";
                notifyKid(c, txt);
                event(c, who + ": " + txt, who, false);
                sendState(c, false);
                break;
            }
            case "block": {
                // Take a normally allowed app away for a while; it closes and leaves the home screen.
                String pkg = m.optString("app");
                long mins = Math.max(1, Math.min(24 * 60, m.optLong("minutes", 60)));
                long now = System.currentTimeMillis();
                long until = Math.min(now, m.optLong("ts", now)) + mins * 60_000L;
                if (until <= now) break;
                Map<String, Long> b = Grants.loadBlocks(c);
                b.put(pkg, until);
                Grants.saveBlocks(c, b);
                Map<String, Long> g = Grants.load(c);
                if (g.remove(pkg) != null) Grants.save(c, g);
                Policy.apply(c);
                String txt = label(c, pkg) + " paused until " + time(c, until);
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt, who, false);
                sendState(c, false);
                break;
            }
            case "unblock": {
                String pkg = m.optString("app");
                Map<String, Long> b = Grants.loadBlocks(c);
                if (b.remove(pkg) == null) break;
                Grants.saveBlocks(c, b);
                Policy.apply(c);
                String txt = label(c, pkg) + " is back";
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt, who, false);
                sendState(c, false);
                break;
            }
            case "deny": {
                String txt = "Request for " + m.optString("label", "an app") + " was declined";
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt, who, false);
                break;
            }
            case "home": {
                // Older parent apps: add puts an app on with the default situations, remove takes it off.
                String pkg = m.optString("app"), op = m.optString("op");
                if ("add".equals(op) && !Policy.isPresent(c, pkg)) break;
                Schedule.setMask(c, pkg, "add".equals(op) ? Schedule.DEFAULT_MASK : 0);
                Policy.apply(c);
                event(c, who + ": " + label(c, pkg) + ("add".equals(op) ? " added to" : " removed from")
                        + " the home screen", who, false);
                sendState(c, true);
                break;
            }
            case "appmode": {
                // Which situations (normal / limited hours / bedtime) an app is allowed in; 0 = only by grant.
                // "set"/"clear" change single situations against the device's current mask, so quick taps
                // from a parent app with slightly old state don't undo each other; "mask" replaces it.
                String pkg = m.optString("app");
                int mask;
                if (m.has("mask")) {
                    mask = m.optInt("mask") & Schedule.ALL;
                } else {
                    Integer cur = Schedule.masks(c).get(pkg);
                    mask = ((cur == null ? 0 : cur) | m.optInt("set")) & ~m.optInt("clear") & Schedule.ALL;
                }
                if (mask != 0 && !Policy.isPresent(c, pkg)) break;
                Schedule.setMask(c, pkg, mask);
                Policy.apply(c);
                event(c, who + ": " + label(c, pkg) + " " + describeMask(mask), who, false);
                sendState(c, true);
                break;
            }
            case "schedule": {
                Schedule.setRules(c, m.optJSONArray("rules"));
                Policy.apply(c);
                event(c, who + " changed the schedule", who, false);
                sendState(c, true);
                break;
            }
            case "mode": {
                // Start limited hours or bedtime by hand for a while ("none" ends it). Counts from when the
                // parent sent it, like grants, so it can't be saved up by keeping the device offline.
                int mode = Schedule.parseMode(m.optString("mode"));
                long now = System.currentTimeMillis();
                long mins = Math.max(1, Math.min(24 * 60, m.optLong("minutes", 60)));
                long until = Math.min(now, m.optLong("ts", now)) + mins * 60_000L;
                if (mode == Schedule.LIMITED || mode == Schedule.BEDTIME) {
                    if (until <= now) break;
                    Schedule.setOverride(c, mode, until);
                } else {
                    Schedule.setOverride(c, 0, 0);
                }
                Policy.apply(c);
                String txt = mode == Schedule.LIMITED || mode == Schedule.BEDTIME
                        ? Schedule.modeLabel(mode) + " started until " + time(c, until)
                        : "Manual limited hours or bedtime ended";
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt, who, false);
                sendState(c, false);
                break;
            }
            case "limits":
                // Daily app time limits, the whole list (a parent's edit replaces it).
                AppTime.setLimits(c, m.optJSONArray("limits"));
                event(c, who + " changed the app time limits", who, false);
                sendState(c, false);
                break;
            case "usage":
                // A parent's report asks for the daily record from a date on.
                AppTime.sendHistory(c, m.optString("since"), m.optString("req"));
                break;
            case "update":
                // "Update now" from a parent: check, download and install when the screen is next off.
                new Thread(() -> Updater.check(c, who), "update").start();
                break;
            case "uninstall":
                // Irreversible, so refused if it arrives late (like deleting files).
                if (!recent(m)) break;
                AppRemover.uninstall(c, m.optString("app"), who);
                break;
            case "offer": {
                // Put an app on, or take it off, the "Give access" list (and the child's Ask picker).
                String pkg = m.optString("app");
                boolean on = m.optBoolean("on", true);
                Policy.setOffered(c, pkg, on);
                event(c, who + ": " + label(c, pkg) + (on ? " put back on" : " taken off") + " the app list", who, false);
                sendState(c, true);
                break;
            }
            case "icons":
                AppIcons.send(c, m.optString("id"), m.optJSONArray("apps"));
                break;
            case "calls": {
                // Safe list and whether outgoing / incoming calls are limited to it.
                CallGuard.setLimits(c, m.optBoolean("limitOut"), m.optBoolean("limitIn"));
                if (m.has("safe")) CallGuard.setSafeList(c, m.optJSONArray("safe"));
                event(c, who + " changed the call settings", who, false);
                sendState(c, true);
                break;
            }
            case "calllog":
                CallLogFeed.sendHistory(c, m.optString("id"));
                break;
            case "contacts":
                contacts(c, m, who);
                break;
            case "files": {
                String op = m.optString("op");
                boolean destructive = "rm".equals(op) || "mv".equals(op) || "put".equals(op);
                if (destructive && !recent(m)) break;
                FileManager.handle(c, m, who, attachmentUrl);
                break;
            }
            case "parentmode": {
                // Remote parent mode: Settings and Play Store unlocked on the device, ending by itself.
                // minutes 0 ends it now; the screen turning off also ends it, as with the PIN.
                if (!live(m)) break;
                long mins = Math.max(0, Math.min(12 * 60, m.optLong("minutes", 15)));
                long until = mins == 0 ? 0 : System.currentTimeMillis() + mins * 60_000L;
                // Store it now so the state sent below already shows it; the UI change runs on the main thread.
                Policy.storeParentUntil(c, until);
                c.getMainExecutor().execute(() -> Policy.setParentMode(c, until));
                String txt = mins == 0 ? "Parent mode ended" : "Parent mode on until " + time(c, until);
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt, who, false);
                sendState(c, false);
                break;
            }
            case "pin": {
                // New parent PIN (4 to 8 digits); never echoed back or logged.
                String pin = m.optString("pin");
                if (pin.length() < 4 || pin.length() > 8 || !pin.matches("[0-9]+")) break;
                Policy.setPin(c, pin);
                event(c, who + " changed the parent PIN", who, false);
                break;
            }
            case "battery": {
                // Low-battery alert threshold, in percent (0 = off); shared by both parents.
                int at = m.optInt("alert", Battery.DEFAULT_ALERT);
                Battery.setAlertAt(c, at);
                event(c, at == 0 ? who + " turned off low-battery alerts"
                        : who + " set the low-battery alert to " + Battery.alertAt(c) + "%", who, false);
                sendState(c, true);
                break;
            }
            case "track":
                // A parent's location history page asks for entries since its newest one.
                LocationLog.sendHistory(c, m.optLong("since", 0), m.optString("id"));
                break;
            case "debug": {
                // Recovery: USB and wireless debugging on for a while (0 turns them off). Only acted on
                // promptly, like other operations that change what the device allows.
                if (!recent(m)) break;
                long mins = Math.max(0, Math.min(240, m.optLong("minutes", 60)));
                if (mins == 0) {
                    Debugging.disable(c);
                    event(c, who + " turned debugging off", who, false);
                } else {
                    new Thread(() -> Debugging.enable(c, mins, who), "debug").start();
                }
                break;
            }
            case "diag":
                // A parent's "Send diagnostics"; the reply goes only to that parent's app.
                Diagnostics.send(c, m.optString("id"));
                break;
            case "locate":
                if (!live(m)) break;
                Locator.locate(c, m.optString("id"));
                break;
            case "ring":
                if (!live(m) && !"stop".equals(m.optString("op"))) break;
                ring(c, m, who);
                break;
            case "screen":
                if (!live(m)) break;
                // The child is always told (no hidden monitoring); an old "notify": false is ignored.
                ScreenService.capture(c, m.optString("id"), who);
                break;
            default:
                break;
        }
    }

    /** {@code contacts} with {@code op} list (default), add {name, number} or remove {contact}. */
    private static void contacts(Context c, JSONObject m, String who) {
        String op = m.optString("op", "list");
        if ("add".equals(op)) {
            String name = m.optString("name").trim(), number = m.optString("number").trim();
            if (name.isEmpty() || number.isEmpty()) return;
            if (Contacts.add(c, name, number)) {
                notifyKid(c, who + " added " + name + " to your contacts");
                event(c, who + ": added contact " + name, who, false);
            } else {
                event(c, "Couldn't add contact " + name, who, false);
            }
        } else if ("remove".equals(op)) {
            if (!recent(m)) return;
            String name = Contacts.remove(c, m.optString("key"));
            if (name != null) {
                notifyKid(c, who + " removed " + name + " from your contacts");
                event(c, who + ": removed contact " + name, who, false);
            }
        }
        Contacts.sendList(c, m.optString("id"));
    }

    /** "allowed in normal and limited hours", "blocked except when allowed for a while", … */
    private static String describeMask(int mask) {
        if (mask == 0) return "blocked except when allowed for a while";
        if (mask == Schedule.ALL) return "always allowed";
        List<String> parts = new ArrayList<>();
        if ((mask & Schedule.NORMAL) != 0) parts.add("normal hours");
        if ((mask & Schedule.LIMITED) != 0) parts.add("limited hours");
        if ((mask & Schedule.BEDTIME) != 0) parts.add("bedtime");
        return "allowed in " + String.join(" and ", parts);
    }

    /** {@code ring} with {@code op} start (default) or stop. Runs on the main thread, as Ringer requires. */
    private static void ring(Context c, JSONObject m, String who) {
        boolean stop = "stop".equals(m.optString("op"));
        int seconds = m.optInt("seconds", Ringer.DEFAULT_SECONDS);
        c.getMainExecutor().execute(() -> {
            if (stop) {
                Ringer.stop(c, who + " stopped the ringing", who);
            } else {
                boolean was = Ringer.isRinging();
                Ringer.start(c, who, seconds);
                if (!was) event(c, who + " is ringing the device", who, false);
            }
        });
    }

    // ---------- outgoing ----------

    static JSONObject msg(String type) throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("t", type);
        o.put("id", Crypto.randomId());
        o.put("ts", System.currentTimeMillis());
        o.put("from", FROM);
        return o;
    }

    /** Something the child or the phone itself did (a request, "Time's up"): parents get a notification. */
    static void event(Context c, String text) {
        event(c, text, null, false);
    }

    /**
     * Reports {@code text} to the parents' activity lists. {@code by} names the parent whose action this was
     * (their own app won't notify them about it); {@code quiet} marks routine news that goes in the list
     * without a notification (e.g. bedtime starting).
     */
    static void event(Context c, String text, String by, boolean quiet) {
        try {
            JSONObject o = msg("event");
            o.put("text", text);
            if (by != null) o.put("by", by);
            if (quiet) o.put("quiet", true);
            publish(c, o, text);
        } catch (Exception ignored) {}
    }

    /** Kid tapped "Ask" on the home screen. */
    static boolean request(Context c, String pkg, long minutes) {
        SharedPreferences p = Policy.prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("last_request", 0) < 60_000) return false;
        p.edit().putLong("last_request", now).apply();
        try {
            JSONObject o = msg("req");
            o.put("req", Crypto.randomId());
            o.put("app", pkg);
            o.put("label", label(c, pkg));
            o.put("minutes", minutes);
            publish(c, o, "Request: " + label(c, pkg));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void sendState(Context c, boolean full) {
        // Starts the call-log feed once its permission exists (it may be granted after the service started).
        CallLogFeed.watch(c);
        try {
            JSONObject o = msg("state");
            JSONObject grants = new JSONObject();
            JSONObject labels = new JSONObject();
            for (Map.Entry<String, Long> e : Grants.load(c).entrySet()) {
                grants.put(e.getKey(), e.getValue());
                labels.put(e.getKey(), label(c, e.getKey()));
            }
            JSONArray home = new JSONArray();
            for (String p : Policy.visibleApps(c)) {
                if (!Policy.isPresent(c, p)) continue;
                home.put(p);
                labels.put(p, label(c, p));
            }
            o.put("name", Policy.prefs(c).getString("device_name", "Device"));
            o.put("grants", grants);
            JSONObject blocks = new JSONObject();
            for (Map.Entry<String, Long> e : Grants.loadBlocks(c).entrySet()) {
                blocks.put(e.getKey(), e.getValue());
                labels.put(e.getKey(), label(c, e.getKey()));
            }
            o.put("blocks", blocks);
            o.put("home", home);
            o.put("parent", Policy.inParentMode(c));
            o.put("parentUntil", Policy.inParentMode(c) ? Policy.prefs(c).getLong("parent_until", 0) : 0);
            o.put("ringing", Ringer.isRinging());
            o.put("version", Updater.installedVersion(c));
            Battery.put(c, o);
            int ready = Updater.readyVersion(c);
            if (ready > 0) o.put("updateReady", ready);
            o.put("mode", Schedule.modeName(Schedule.currentMode(c)));
            int manual = Schedule.overrideMode(c);
            if (manual != 0) {
                o.put("manual", new JSONObject().put("mode", Schedule.modeName(manual))
                        .put("until", Schedule.overrideUntil(c)));
            }
            o.put("modeUntil", Schedule.currentModeUntil(c));
            AppTime.putState(c, o);
            if (full) {
                JSONArray inst = new JSONArray();
                for (String p : otherApps(c)) {
                    inst.put(p);
                    labels.put(p, label(c, p));
                }
                o.put("others", inst);
                JSONArray off = new JSONArray();
                for (String p : notOfferedApps(c)) {
                    off.put(p);
                    labels.put(p, label(c, p));
                }
                o.put("notOffered", off);
                o.put("removable", new JSONArray(AppRemover.removableOf(c, installedOthersAndHome(c))));
                o.put("masks", new JSONObject(Schedule.masks(c)));
                o.put("calls", CallGuard.settingsJson(c));
                o.put("can", new JSONObject()
                        .put("files", FileManager.permitted())
                        .put("calllog", CallLogFeed.permitted(c)));
                o.put("schedule", Schedule.rulesJson(c));
                o.put("battAlert", Battery.alertAt(c));
            }
            // What the child is doing right now, for the parents' status card: screen on or off, and the app in front.
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            boolean screenOn = pm == null || pm.isInteractive();
            o.put("screenOn", screenOn);
            JSONObject using = screenOn ? AppUsage.current(c) : null;
            if (using != null) {
                o.put("using", using);
                labels.put(using.getString("app"), label(c, using.getString("app")));
            }
            o.put("labels", labels);
            publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "state: " + e);
        }
    }

    /**
     * Apps that could be granted or added to the home screen: launchable, not already on it, and not taken
     * off the list by a parent ({@link Policy#notOffered}).
     */
    static List<String> otherApps(Context c) {
        List<String> out = installedOthers(c);
        out.removeAll(Policy.notOffered(c));
        return out;
    }

    /** Apps a parent took off the list that are still installed, so they can be put back. */
    static List<String> notOfferedApps(Context c) {
        List<String> out = installedOthers(c);
        out.retainAll(Policy.notOffered(c));
        return out;
    }

    /** Every app parents see in their list: home-screen apps, other launchable apps and hidden ones. */
    private static List<String> installedOthersAndHome(Context c) {
        List<String> out = new ArrayList<>(Policy.visibleApps(c));
        for (String p : installedOthers(c)) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** Launchable apps that are not on the home screen, nor system parts that are never hidden. */
    private static List<String> installedOthers(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new LinkedHashSet<>(Policy.visibleApps(c));
        skip.addAll(Arrays.asList(Policy.SUPPORT));
        skip.addAll(Arrays.asList(Policy.NEVER_HIDE));
        // Except those listed for parents (starting on the hidden list), such as Settings.
        skip.removeAll(Arrays.asList(Policy.HIDDEN_FROM_LIST_BY_DEFAULT));
        skip.add(c.getPackageName());
        Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> out = new LinkedHashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(li, PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
            String p = ri.activityInfo.packageName;
            if (!skip.contains(p) && Policy.isPresent(c, p)) out.add(p);
        }
        // Wi-Fi and Bluetooth are listed like apps (see BuiltInApps).
        for (String id : BuiltInApps.ALL) if (!skip.contains(id)) out.add(id);
        return new ArrayList<>(out);
    }

    static String label(Context c, String pkg) {
        if (BuiltInApps.is(pkg)) return BuiltInApps.label(pkg);
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return c.getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    static String time(Context c, long t) {
        return android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(t));
    }

    /** Requests, notable events and answers a parent is waiting for (location, screenshot). */
    private static boolean urgent(JSONObject o) {
        switch (o.optString("t")) {
            case "req":
            case "loc":
            case "shot":
                return true;
            case "event":
                return !o.optBoolean("quiet");
            default:
                return false;
        }
    }

    /**
     * One background thread for all sends, so messages reach ntfy in the order they were made (a full state
     * must not overtake a newer partial one).
     */
    private static final java.util.concurrent.ExecutorService SENDER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    static void publish(Context c, JSONObject o, String notifyText) {
        if (!paired(c)) return;
        SENDER.execute(() -> send(c, o));
    }

    /**
     * Publishes {@code o} and waits up to {@code timeoutMs} for the hub to accept it, for messages that
     * must not be lost (see {@link Outbox}). Goes through the same sender thread, so ordering holds; never
     * call it from that thread.
     */
    static boolean publishNow(Context c, JSONObject o, long timeoutMs) {
        if (!paired(c)) return false;
        java.util.concurrent.Future<Boolean> f = SENDER.submit(() -> send(c, o));
        try {
            return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Not started yet: don't send it late behind the caller's back (the caller retries).
            f.cancel(false);
            Log.w(Policy.TAG, "publish now: " + e);
            return false;
        }
    }

    /** Seals and posts {@code o}; true if the hub accepted it. Runs on the sender thread. */
    private static boolean send(Context c, JSONObject o) {
        try {
            byte[] key = Crypto.key(secret(c));
            String body = Crypto.seal(key, o.toString());
            if (body.length() <= MAX_INLINE) {
                return post(c, body.getBytes(StandardCharsets.US_ASCII), null, urgent(o));
            }
            // ntfy turns bodies over 4 KB into files the apps wouldn't read, so send a small "big"
            // message carrying the real one as an encrypted attachment.
            JSONObject wrapper = msg("big");
            byte[] enc = Crypto.sealBytes(key, o.toString().getBytes(StandardCharsets.UTF_8),
                    "phonelock/blob/" + wrapper.getString("id"));
            return post(c, enc, Crypto.seal(key, wrapper.toString()), urgent(o));
        } catch (Exception e) {
            Log.w(Policy.TAG, "publish: " + e);
            return false;
        }
    }

    /**
     * Publishes {@code o} with an encrypted binary attachment (a screenshot). ntfy stores the blob as a
     * file (kept ~3 h on ntfy.sh) and delivers the sealed message as the text of the same ntfy message.
     * The blob's AAD is {@code phonelock/blob/<message id>}, binding it to this message.
     */
    static void publishWithBlob(Context c, JSONObject o, byte[] blob) {
        if (!paired(c)) return;
        SENDER.execute(() -> {
            try {
                byte[] key = Crypto.key(secret(c));
                String sealed = Crypto.seal(key, o.toString());
                byte[] enc = Crypto.sealBytes(key, blob, "phonelock/blob/" + o.getString("id"));
                post(c, enc, sealed, urgent(o));
            } catch (Exception e) {
                Log.w(Policy.TAG, "publish blob: " + e);
            }
        });
    }

    /**
     * Sends one ntfy message. With {@code messageHeader} null, {@code body} is the message; otherwise
     * {@code body} is uploaded as an attachment and {@code messageHeader} becomes the message text.
     * {@code urgent} asks the hub for a high-priority push, which Android delivers even in deep sleep; used
     * only for things a parent should see promptly (it also caps how often Firebase allows it).
     * Returns whether the hub accepted it. Blocks; call off the main thread.
     */
    private static boolean post(Context c, byte[] body, String messageHeader, boolean urgent) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(
                RemoteService.server(c) + "/" + RemoteService.topic(c)).openConnection();
        h.setRequestMethod(messageHeader == null ? "POST" : "PUT");
        h.setDoOutput(true);
        h.setConnectTimeout(15_000);
        h.setReadTimeout(30_000);
        if (urgent) h.setRequestProperty("Priority", "4");
        if (messageHeader != null) {
            h.setRequestProperty("Filename", "blob.bin");
            h.setRequestProperty("Message", messageHeader);
            h.setFixedLengthStreamingMode(body.length);
        }
        try (OutputStream os = h.getOutputStream()) {
            os.write(body);
        }
        int code = h.getResponseCode();
        h.disconnect();
        if (code < 200 || code > 299) {
            Log.w(Policy.TAG, "ntfy publish HTTP " + code);
            return false;
        }
        return true;
    }

    static void notifyKid(Context c, String text) {
        notifyKid(c, text, (int) (System.currentTimeMillis() & 0x7fffffff));
    }

    /** Posts a notice to the child under notification {@code id} (reusing an id replaces that notice). */
    static void notifyKid(Context c, String text, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("parent", "From parents",
                NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, "parent")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(text)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
