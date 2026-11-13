package home.phonelock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The child's emergency alert (the SOS button). Starting one sends an {@code sos} message to every parent at
 * once, with where the device last was and its battery, then a fresh location fix as soon as there is one,
 * and keeps the location log reading every minute for {@link #TRACK_MS}. She can add a message and, within
 * {@link #CANCEL_MS}, cancel it as sent by mistake. Parents answer with {@code sosack} ("on it"), which she
 * sees on the emergency screen.
 *
 * <p>Every part is retried until it reaches the hub (a phone with no signal keeps trying), each with a fixed
 * message id so parents drop duplicates. State is kept in prefs so a restart doesn't lose an alert.
 */
final class Emergency {
    /** Location readings come every minute for this long after an alert. */
    static final long TRACK_MS = 30 * 60_000L;
    static final long TRACK_EVERY_MS = 60_000L;
    /** "Sent by mistake" is offered this long after starting. */
    static final long CANCEL_MS = 2 * 60_000L;
    /** How long sending keeps retrying (signal may come back). */
    private static final long RETRY_FOR_MS = 60 * 60_000L;
    private static final long RETRY_EVERY_MS = 15_000L;
    private static final long SEND_TIMEOUT_MS = 15_000L;

    /** Sent to this app when anything about the alert changed (sent, a parent answered), for the screen. */
    static final String ACTION_CHANGED = "home.phonelock.EMERGENCY_CHANGED";

    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private Emergency() {}

    private static SharedPreferences prefs(Context c) {
        return Policy.prefs(c);
    }

    /** The alert in progress (started within {@link #TRACK_MS}, not cancelled), or "" if none. */
    static String activeId(Context c) {
        String id = prefs(c).getString("sos_id", "");
        long at = prefs(c).getLong("sos_at", 0);
        boolean live = !id.isEmpty() && System.currentTimeMillis() - at < TRACK_MS && !prefs(c).getBoolean("sos_cancelled", false);
        return live ? id : "";
    }

    static long startedAt(Context c) {
        return prefs(c).getLong("sos_at", 0);
    }

    /** Whether the location log should read every minute now. */
    static boolean tracking(Context c) {
        return !activeId(c).isEmpty();
    }

    /** Whether "Sent by mistake" is still offered. */
    static boolean canCancel(Context c) {
        return !activeId(c).isEmpty() && System.currentTimeMillis() - startedAt(c) < CANCEL_MS;
    }

    /** Whether every queued part has reached the hub. */
    static boolean allSent(Context c) {
        return queue(c).length() == 0;
    }

    /** Parents who said they're on it, in order. */
    static java.util.List<String> acks(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("sos_acks", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    /** The messages she sent with the current alert, in order. */
    static java.util.List<String> messages(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("sos_msgs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    /** Whether the last alert was cancelled as sent by mistake. */
    static boolean cancelled(Context c) {
        return prefs(c).getBoolean("sos_cancelled", false);
    }

    // ---------- the child's actions ----------

    /** Starts an alert (or returns the one in progress): tells the parents, then fetches a fresh location. */
    static String start(Context c) {
        Context app = c.getApplicationContext();
        String current = activeId(app);
        if (!current.isEmpty()) return current;

        String id = Crypto.randomId();
        long now = System.currentTimeMillis();
        prefs(app).edit().putString("sos_id", id).putLong("sos_at", now).putBoolean("sos_cancelled", false)
                .putString("sos_acks", "[]").putString("sos_msgs", "[]").putString("sos_queue", "[]").commit();
        try {
            JSONObject o = part(app, id, "start");
            JSONObject where = LocationLog.lastPlace(app);
            if (where != null) o.put("where", where);
            String number = ownNumber(app);
            if (number != null) o.put("number", number);
            enqueue(app, o);
        } catch (Exception e) {
            Log.w(Policy.TAG, "sos start: " + e);
        }

        // Readings every minute from now on, starting with a fresh fix sent as its own part.
        LocationLog.schedule(app);
        WORKER.execute(() -> {
            Location fix = Locator.fixNow(app, true);
            if (fix == null || !id.equals(activeId(app))) return;
            try {
                JSONObject o = part(app, id, "where");
                o.put("where", new JSONObject().put("lat", fix.getLatitude()).put("lng", fix.getLongitude())
                        .put("at", fix.getTime()).put("acc", Math.round(fix.getAccuracy())));
                enqueue(app, o);
            } catch (Exception e) {
                Log.w(Policy.TAG, "sos fix: " + e);
            }
        });
        Remote.sendState(app, false);
        return id;
    }

    /** Adds her message ({@code text}) to the alert in progress. */
    static void message(Context c, String text) {
        String id = activeId(c);
        if (id.isEmpty() || text == null || text.trim().isEmpty()) return;
        try {
            JSONArray sent = new JSONArray(prefs(c).getString("sos_msgs", "[]"));
            sent.put(text.trim());
            prefs(c).edit().putString("sos_msgs", sent.toString()).apply();
            JSONObject o = part(c, id, "msg");
            o.put("text", text.trim());
            enqueue(c, o);
        } catch (Exception e) {
            Log.w(Policy.TAG, "sos msg: " + e);
        }
    }

    /** Cancels the alert in progress as sent by mistake. */
    static void cancel(Context c) {
        String id = activeId(c);
        if (id.isEmpty()) return;
        try {
            enqueue(c, part(c, id, "cancel"));
        } catch (Exception e) {
            Log.w(Policy.TAG, "sos cancel: " + e);
        }
        prefs(c).edit().putBoolean("sos_cancelled", true).apply();
        LocationLog.schedule(c);
        changed(c);
    }

    // ---------- parents' answers ----------

    /** A parent ({@code who}) said they're on it for alert {@code sos}. */
    static void acked(Context c, String sos, String who) {
        if (!sos.equals(prefs(c).getString("sos_id", ""))) return;
        java.util.List<String> acks = acks(c);
        if (acks.contains(who)) return;
        acks.add(who);
        prefs(c).edit().putString("sos_acks", new JSONArray(acks).toString()).apply();
        Remote.notifyKid(c, who + " got your emergency alert and is on it");
        changed(c);
    }

    // ---------- sending ----------

    /** One part of alert {@code id}: an {@code sos} message with {@code op}, the battery and a fixed id. */
    private static JSONObject part(Context c, String id, String op) throws Exception {
        JSONObject o = Remote.msg("sos");
        o.put("sos", id);
        o.put("op", op);
        o.put("since", startedAt(c));
        Battery.put(c, o);
        return o;
    }

    private static JSONArray queue(Context c) {
        try {
            return new JSONArray(prefs(c).getString("sos_queue", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Queues {@code o} and sends everything waiting, in order, retrying until it goes through. */
    private static void enqueue(Context c, JSONObject o) {
        synchronized (Emergency.class) {
            JSONArray q = queue(c);
            q.put(o);
            prefs(c).edit().putString("sos_queue", q.toString()).commit();
        }
        changed(c);
        WORKER.execute(() -> flush(c.getApplicationContext()));
    }

    /** Sends the queue front to back; on a failure, tries again shortly (for up to an hour). Runs on WORKER. */
    private static void flush(Context c) {
        while (true) {
            JSONObject next;
            synchronized (Emergency.class) {
                JSONArray q = queue(c);
                next = q.length() == 0 ? null : q.optJSONObject(0);
            }
            if (next == null) return;
            if (!Remote.paired(c) || System.currentTimeMillis() - next.optLong("ts") > RETRY_FOR_MS) {
                drop(c);
                continue;
            }
            if (!Remote.publishNow(c, next, SEND_TIMEOUT_MS)) {
                try {
                    Thread.sleep(RETRY_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            drop(c);
            changed(c);
        }
    }

    private static void drop(Context c) {
        synchronized (Emergency.class) {
            JSONArray q = queue(c), rest = new JSONArray();
            for (int i = 1; i < q.length(); i++) rest.put(q.opt(i));
            prefs(c).edit().putString("sos_queue", rest.toString()).commit();
        }
    }

    /** Resumes sending after a restart (the service calls this as it starts). */
    static void resume(Context c) {
        if (queue(c).length() > 0) WORKER.execute(() -> flush(c.getApplicationContext()));
    }

    private static void changed(Context c) {
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
    }

    /** This phone's own number, so parents can call back; null if Android won't say. */
    private static String ownNumber(Context c) {
        try {
            android.telephony.TelephonyManager tm = c.getSystemService(android.telephony.TelephonyManager.class);
            android.telephony.SubscriptionManager sm = c.getSystemService(android.telephony.SubscriptionManager.class);
            int sub = android.telephony.SubscriptionManager.getDefaultSubscriptionId();
            String n = sm != null ? sm.getPhoneNumber(sub) : null;
            if ((n == null || n.isEmpty()) && tm != null) n = tm.getLine1Number();
            return n == null || n.isEmpty() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }
}
