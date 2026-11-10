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

    private static synchronized boolean fresh(Context c, JSONObject m) {
        long ts = m.optLong("ts"), now = System.currentTimeMillis();
        if (ts <= 0 || now - ts > MAX_AGE_MS || ts - now > MAX_FUTURE_MS) return false;
        String id = m.optString("id");
        if (id.isEmpty()) return false;
        SharedPreferences p = Policy.prefs(c);
        List<String> seen = new ArrayList<>(Arrays.asList(p.getString("seen_ids", "").split(",")));
        if (seen.contains(id)) return false;
        seen.add(id);
        while (seen.size() > 500) seen.remove(0);
        p.edit().putString("seen_ids", String.join(",", seen)).apply();
        return true;
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
                    event(c, label(c, pkg) + " is not installed");
                    break;
                }
                Map<String, Long> g = Grants.load(c);
                long until = System.currentTimeMillis() + mins * 60_000L;
                g.put(pkg, until);
                Grants.save(c, g);
                Policy.apply(c);
                String txt = label(c, pkg) + " allowed until " + time(c, until);
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt);
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
                event(c, who + ": " + txt);
                sendState(c, false);
                break;
            }
            case "deny": {
                String txt = "Request for " + m.optString("label", "an app") + " was declined";
                notifyKid(c, txt + " (" + who + ")");
                event(c, who + ": " + txt);
                break;
            }
            case "home": {
                String pkg = m.optString("app"), op = m.optString("op");
                List<String> apps = new ArrayList<>(Policy.visibleApps(c));
                if ("add".equals(op) && !apps.contains(pkg) && Policy.isPresent(c, pkg)) apps.add(pkg);
                else if ("remove".equals(op)) apps.remove(pkg);
                Policy.prefs(c).edit().putString("apps", String.join(",", apps)).apply();
                Policy.apply(c);
                event(c, who + ": " + label(c, pkg) + ("add".equals(op) ? " added to" : " removed from")
                        + " the home screen");
                sendState(c, true);
                break;
            }
            default:
                break;
        }
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

    static void event(Context c, String text) {
        try {
            JSONObject o = msg("event");
            o.put("text", text);
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
            o.put("name", Policy.prefs(c).getString("device_name", "Phone"));
            o.put("grants", grants);
            o.put("home", home);
            o.put("parent", Policy.inParentMode(c));
            if (full) {
                JSONArray inst = new JSONArray();
                for (String p : otherApps(c)) {
                    inst.put(p);
                    labels.put(p, label(c, p));
                }
                o.put("others", inst);
            }
            o.put("labels", labels);
            publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "state: " + e);
        }
    }

    /** Launchable apps that are not on the home screen (i.e. what could be granted or added). */
    static List<String> otherApps(Context c) {
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

    static void publish(Context c, JSONObject o, String notifyText) {
        if (!paired(c)) return;
        new Thread(() -> {
            try {
                String body = Crypto.seal(Crypto.key(secret(c)), o.toString());
                HttpURLConnection h = (HttpURLConnection) new URL(
                        RemoteService.server(c) + "/" + RemoteService.topic(c)).openConnection();
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setConnectTimeout(15_000);
                h.setReadTimeout(15_000);
                try (OutputStream os = h.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.US_ASCII));
                }
                h.getResponseCode();
                h.disconnect();
            } catch (Exception e) {
                Log.w(Policy.TAG, "publish: " + e);
            }
        }).start();
    }

    static void notifyKid(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("parent", "From parents",
                NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, "parent")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(text)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
    }
}
