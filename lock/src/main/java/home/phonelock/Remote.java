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
            dispatch(c, m, from);
        } catch (Exception e) {
            Log.w(Policy.TAG, "remote: " + e);
        }
    }

    /**
     * Drops messages outside the freshness window and ones already handled. Seen ids are kept with their
     * timestamps for the whole window (not just the last N), so a captured message can't be replayed later.
     */
    private static synchronized boolean fresh(Context c, JSONObject m) {
        long ts = m.optLong("ts"), now = System.currentTimeMillis();
        if (ts <= 0 || now - ts > MAX_AGE_MS || ts - now > MAX_FUTURE_MS) return false;
        String id = m.optString("id");
        if (id.isEmpty() || id.contains(",") || id.contains(":")) return false;
        SharedPreferences p = Policy.prefs(c);
        List<String> kept = new ArrayList<>();
        for (String e : p.getString("seen_ids", "").split(",")) {
            int colon = e.lastIndexOf(':');
            if (colon <= 0) continue; // old format without a timestamp: drop
            long seenTs;
            try { seenTs = Long.parseLong(e.substring(colon + 1)); } catch (NumberFormatException x) { continue; }
            if (now - seenTs > MAX_AGE_MS + MAX_FUTURE_MS) continue;
            if (e.substring(0, colon).equals(id)) return false;
            kept.add(e);
        }
        kept.add(id + ":" + ts);
        while (kept.size() > 5000) kept.remove(0); // safety cap, far above normal traffic
        p.edit().putString("seen_ids", String.join(",", kept)).apply();
        return true;
    }

    /** Commands that only make sense right away (ringing, locating, screenshots) are dropped after this. */
    private static final long LIVE_MAX_AGE_MS = 2 * 60_000L;

    private static boolean live(JSONObject m) {
        return System.currentTimeMillis() - m.optLong("ts") <= LIVE_MAX_AGE_MS;
    }

    private static void dispatch(Context c, JSONObject m, String from) throws Exception {
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
                String pkg = m.optString("app");
                int mask = m.optInt("mask") & Schedule.ALL;
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
            o.put("home", home);
            o.put("parent", Policy.inParentMode(c));
            o.put("ringing", Ringer.isRinging());
            o.put("mode", Schedule.modeName(Schedule.currentMode(c)));
            o.put("modeUntil", Schedule.currentModeUntil(c));
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
                o.put("masks", new JSONObject(Schedule.masks(c)));
                o.put("schedule", Schedule.rulesJson(c));
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

    /** Launchable apps that are not on the home screen, nor system parts that are never hidden. */
    private static List<String> installedOthers(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new LinkedHashSet<>(Policy.visibleApps(c));
        skip.addAll(Arrays.asList(Policy.SUPPORT));
        skip.addAll(Arrays.asList(Policy.NEVER_HIDE));
        skip.add(c.getPackageName());
        Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> out = new LinkedHashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(li, PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
            String p = ri.activityInfo.packageName;
            if (!skip.contains(p) && Policy.isPresent(c, p)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    static String label(Context c, String pkg) {
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
        SENDER.execute(() -> {
            try {
                byte[] key = Crypto.key(secret(c));
                String body = Crypto.seal(key, o.toString());
                if (body.length() <= MAX_INLINE) {
                    post(c, body.getBytes(StandardCharsets.US_ASCII), null, urgent(o));
                } else {
                    // ntfy turns bodies over 4 KB into files the apps wouldn't read, so send a small "big"
                    // message carrying the real one as an encrypted attachment.
                    JSONObject wrapper = msg("big");
                    byte[] enc = Crypto.sealBytes(key, o.toString().getBytes(StandardCharsets.UTF_8),
                            "phonelock/blob/" + wrapper.getString("id"));
                    post(c, enc, Crypto.seal(key, wrapper.toString()), urgent(o));
                }
            } catch (Exception e) {
                Log.w(Policy.TAG, "publish: " + e);
            }
        });
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
     * Blocks; call off the main thread.
     */
    private static void post(Context c, byte[] body, String messageHeader, boolean urgent) throws Exception {
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
        if (code < 200 || code > 299) Log.w(Policy.TAG, "ntfy publish HTTP " + code);
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
