package home.phonelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Messages the parents must get even if the device is offline when they happen: low-battery alerts and the
 * location log. Ordinary messages are sent once and lost without a connection; these are kept until the hub
 * accepts them, and retried whenever the device reconnects or takes its next location reading.
 */
final class Outbox {
    /** How long to wait for one send (longer than a post's own 15 s connect + 30 s read limits). */
    private static final long SEND_TIMEOUT_MS = 60_000;
    /** Alerts older than this are dropped rather than delivered stale (parents keep message ids 12 h). */
    private static final long MAX_ALERT_AGE_MS = 11 * 3600_000L;

    /** One flush at a time, off the sender thread (a flush waits on sends made there). */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private Outbox() {}

    /** Queues an alert for the parents (a notification on their phones) and tries to send it now. */
    static void alert(Context c, String text) {
        add(c, text, false);
    }

    /** Queues routine news for the parents' activity lists, without a notification. */
    static void note(Context c, String text) {
        add(c, text, true);
    }

    private static void add(Context c, String text, boolean quiet) {
        if (!Remote.paired(c)) return; // nobody to tell yet; don't pile up notes
        synchronized (Outbox.class) {
            try {
                SharedPreferences p = Policy.prefs(c);
                JSONArray a = new JSONArray(p.getString("outbox_alerts", "[]"));
                // The message id is fixed now and reused on every retry, so if a send that timed out did
                // reach the hub after all, the parents drop the retry as a duplicate.
                a.put(new JSONObject().put("text", text).put("quiet", quiet).put("at", System.currentTimeMillis())
                        .put("id", Crypto.randomId()));
                p.edit().putString("outbox_alerts", a.toString()).commit();
            } catch (Exception e) {
                Log.w(Policy.TAG, "outbox: " + e);
            }
        }
        flush(c);
    }

    /** How many alerts are waiting to be sent (for diagnostics). */
    static int pendingAlerts(Context c) {
        synchronized (Outbox.class) {
            try {
                return new JSONArray(Policy.prefs(c).getString("outbox_alerts", "[]")).length();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static volatile long lastFlush;

    /**
     * Sends whatever is waiting: alerts first, then location readings. Returns at once. Called on every
     * service start (each return to the home screen), so at most once a minute.
     */
    static void flush(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastFlush < 60_000) return;
        lastFlush = now;
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            if (!Remote.paired(app)) return;
            flushAlerts(app);
            LocationLog.flush(app, SEND_TIMEOUT_MS, false);
        });
    }

    /**
     * Like {@link #flush} but always sends a location message (a heartbeat with the battery level), and
     * waits for it: the location tick holds a wake lock only until this returns.
     */
    static void flushAndHeartbeat(Context c) {
        Context app = c.getApplicationContext();
        java.util.concurrent.Future<?> f = WORKER.submit(() -> {
            if (!Remote.paired(app)) return;
            flushAlerts(app);
            LocationLog.flush(app, SEND_TIMEOUT_MS, true);
        });
        try {
            f.get(SEND_TIMEOUT_MS + 10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(Policy.TAG, "outbox heartbeat: " + e);
        }
    }

    /** Sends queued alerts in order, stopping at the first that doesn't go through. */
    private static void flushAlerts(Context c) {
        while (true) {
            JSONObject next;
            synchronized (Outbox.class) {
                next = peekAlert(c);
            }
            if (next == null) return;

            long at = next.optLong("at");
            boolean sent = true;
            if (System.currentTimeMillis() - at < MAX_ALERT_AGE_MS) {
                try {
                    JSONObject o = Remote.msg("event");
                    if (next.has("id")) o.put("id", next.getString("id"));
                    // A late alert says when it happened, so "Battery low" from an hour ago reads as such.
                    boolean late = System.currentTimeMillis() - at > 2 * 60_000L;
                    o.put("text", next.optString("text") + (late ? " (at " + Remote.time(c, at) + ")" : ""));
                    if (next.optBoolean("quiet")) o.put("quiet", true);
                    sent = Remote.publishNow(c, o, SEND_TIMEOUT_MS);
                } catch (Exception e) {
                    sent = false;
                }
            }
            if (!sent) return;
            synchronized (Outbox.class) {
                dropAlert(c, at);
            }
        }
    }

    private static JSONObject peekAlert(Context c) {
        try {
            JSONArray a = new JSONArray(Policy.prefs(c).getString("outbox_alerts", "[]"));
            return a.length() == 0 ? null : a.getJSONObject(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes the first queued alert if it is still the one made at {@code at}. */
    private static void dropAlert(Context c, long at) {
        try {
            SharedPreferences p = Policy.prefs(c);
            JSONArray a = new JSONArray(p.getString("outbox_alerts", "[]"));
            if (a.length() > 0 && a.getJSONObject(0).optLong("at") == at) a.remove(0);
            p.edit().putString("outbox_alerts", a.toString()).commit();
        } catch (Exception e) {
            Log.w(Policy.TAG, "outbox: " + e);
        }
    }
}
