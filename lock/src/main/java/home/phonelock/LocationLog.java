package home.phonelock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A log of where the device has been, for the parents' "Location history": a reading every 15 minutes
 * (every 2 minutes while she's moving, plus any fixes other apps get anyway),
 * kept on the device for 7 days and sent to the parents as it is taken (or, when offline, once the
 * connection is back).
 *
 * <p>Each entry is a place and a time span ({@code t} to {@code to}): a reading close to the previous one
 * extends that entry instead of adding a new row, so "home all afternoon" is one row. While the device stays
 * on the same Wi-Fi network as the last reading, no new fix is taken at all (saving battery); the entry is
 * just extended. Every tick also sends a heartbeat with the battery level, so parents can tell "still there"
 * from "no news".
 *
 * <p>Entry fields: {@code t} first seen, {@code to} last seen, {@code lat}/{@code lng}, {@code acc} metres,
 * {@code batt}/{@code chg} at the last reading; kept on the device only: {@code u} last changed (the send
 * cursor), {@code f} time of the last real fix, {@code w} Wi-Fi network (BSSID).
 */
final class LocationLog {
    static final long EVERY_MS = 15 * 60_000L;
    /** While she's on the move, readings come this often, so the trail shows the route, not just stops. */
    static final long MOVING_EVERY_MS = 2 * 60_000L;
    /** How long after the last movement to keep sampling often (a stop at a red light isn't arriving). */
    private static final long MOVING_HOLD_MS = 6 * 60_000L;
    /** Passive fixes (from other apps) less precise than this are ignored. */
    private static final float PASSIVE_MAX_ACCURACY_M = 100;
    private static final long KEEP_MS = 7 * 24 * 3600_000L;
    private static final int MAX_ENTRIES = 2000;
    /** Readings within this distance of the last entry extend it (a fix's own accuracy can widen it). */
    private static final float SAME_PLACE_M = 100;
    private static final float SAME_PLACE_MAX_M = 300;
    /** Even on the same Wi-Fi, take a real fix this often, in case the network moved (a hotspot). */
    private static final long REFIX_MS = 3600_000L;
    /** A gap longer than this starts a new entry even at the same place, so a gap in the log stays visible. */
    private static final long MAX_GAP_MS = 2 * EVERY_MS + 5 * 60_000L;
    /** Entries per "track" message; larger batches go in several. */
    private static final int BATCH = 300;
    /** Upper bound on one tick: fix (35 s) plus queued alerts and the location send. */
    private static final long TICK_WAKE_MS = 180_000;

    private LocationLog() {}

    // ---------- schedule ----------

    /**
     * Sets the next tick. Uses an alarm that fires in Doze (a sleeping thread would stall while the CPU is
     * suspended); Android may delay it by a few minutes when idle, which is fine for a 15-minute log.
     */
    static void schedule(Context c) {
        // Every minute during an emergency alert, so parents can follow where she is.
        long every = Emergency.tracking(c) ? Emergency.TRACK_EVERY_MS : moving(c) ? MOVING_EVERY_MS : EVERY_MS;
        long now = System.currentTimeMillis();
        // Called on every service start (each return to the home screen): leave a pending alarm alone unless
        // it's further off than the current interval allows, or an overdue tick would keep being pushed back.
        long next = Policy.prefs(c).getLong("track_next_at", 0);
        if (next > now && next <= now + every) return;
        long last = Policy.prefs(c).getLong("track_tick", 0);
        long wait = Math.max(Math.min(60_000, every), last + every - now);
        wait = Math.min(wait, every);
        Policy.prefs(c).edit().putLong("track_next_at", now + wait).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + wait,
                tickIntent(c));
    }

    private static PendingIntent tickIntent(Context c) {
        return PendingIntent.getBroadcast(c, 11, new Intent(c, Tick.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The alarm: takes a reading under a wake lock, sends it, and sets the next alarm. The work runs on its
     * own thread and onReceive returns at once (a receiver held longer than ~10 s is an ANR); the wake lock
     * keeps the CPU up and the foreground service keeps the process alive meanwhile.
     */
    public static class Tick extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            Context app = c.getApplicationContext();
            PowerManager.WakeLock wl = app.getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonelock:track");
            wl.acquire(TICK_WAKE_MS);
            new Thread(() -> {
                try {
                    Policy.prefs(app).edit().putLong("track_tick", System.currentTimeMillis()).apply();
                    sample(app);
                    Outbox.flushAndHeartbeat(app);
                } catch (Exception e) {
                    Log.w(Policy.TAG, "track tick: " + e);
                } finally {
                    schedule(app);
                    if (wl.isHeld()) wl.release();
                }
            }, "track").start();
        }
    }

    // ---------- readings ----------

    /** Takes one reading and records it. Blocks for up to the fix timeout; call off the main thread. */
    static void sample(Context c) {
        if (!Remote.paired(c)) return;
        long now = System.currentTimeMillis();
        String wifi = bssid(c);
        boolean sameWifi;
        synchronized (LocationLog.class) {
            JSONArray log = load(c);
            JSONObject last = log.length() > 0 ? log.optJSONObject(log.length() - 1) : null;
            sameWifi = last != null && wifi != null && wifi.equals(last.optString("w", null))
                    && now - last.optLong("to") < MAX_GAP_MS;
            // Same Wi-Fi as last time and recently fixed: she's still there, no GPS needed. Not during an
            // emergency alert, when every reading is a real fix.
            if (sameWifi && now - last.optLong("f") < REFIX_MS && !Emergency.tracking(c)) {
                extend(c, last, now, null, wifi);
                save(c, log);
                return;
            }
        }

        Location fix = Locator.fixNow(c, Emergency.tracking(c));
        // Getting a fix can take up to 40 s; what happens next is stamped with the time it arrived.
        now = System.currentTimeMillis();
        // A stale last-known fix would put her somewhere she was hours ago, so it counts as none.
        boolean fresh = fix != null && now - fix.getTime() <= EVERY_MS;

        synchronized (LocationLog.class) {
            JSONArray log = load(c);
            JSONObject last = log.length() > 0 ? log.optJSONObject(log.length() - 1) : null;
            if (!fresh) {
                // Still on the same Wi-Fi: she's still there even without a fix (indoors, say), so keep
                // the entry going. Otherwise skip; the heartbeat still reports battery and "seen".
                if (sameWifi && last != null) {
                    extend(c, last, now, null, wifi);
                    save(c, log);
                }
                return;
            }
            record(c, log, last, now, fix, wifi);
        }
    }

    /**
     * Adds [fix] to the log: extends the last entry if it's the same place, else starts a new one and marks
     * her as moving (so the next readings come every 2 minutes). Call with the log lock held.
     */
    private static void record(Context c, JSONArray log, JSONObject last, long now, Location fix, String wifi) {
        if (last != null && now - last.optLong("to") < MAX_GAP_MS && samePlace(last, fix)) {
            extend(c, last, now, fix, wifi);
        } else {
            log.put(entry(c, now, fix, wifi));
            // Only a precise fix counts as moving (and brings readings every 2 minutes).
            if (last != null && fix.hasAccuracy() && fix.getAccuracy() <= SAME_PLACE_MAX_M) {
                Policy.prefs(c).edit().putLong("track_moving_until", now + MOVING_HOLD_MS).apply();
            }
        }
        save(c, prune(log, now));
    }

    /** Whether she moved recently, so readings should come often. */
    static boolean moving(Context c) {
        return System.currentTimeMillis() < Policy.prefs(c).getLong("track_moving_until", 0);
    }

    /**
     * A location another app on the device asked for (maps, a fitness app), received for free through the
     * passive provider: recorded like a reading, without this app ever turning on GPS. Sent with the next
     * tick. Moving to a new place brings the next tick forward.
     */
    static void recordPassive(Context c, Location fix) {
        long now = System.currentTimeMillis();
        if (!Remote.paired(c) || !fix.hasAccuracy() || fix.getAccuracy() > PASSIVE_MAX_ACCURACY_M) return;
        if (now - fix.getTime() > 60_000) return;
        boolean wasMoving = moving(c);
        synchronized (LocationLog.class) {
            JSONArray log = load(c);
            JSONObject last = log.length() > 0 ? log.optJSONObject(log.length() - 1) : null;
            record(c, log, last, now, fix, bssid(c));
        }
        if (!wasMoving && moving(c)) schedule(c);
    }

    private static boolean samePlace(JSONObject e, Location fix) {
        float[] d = new float[1];
        Location.distanceBetween(e.optDouble("lat"), e.optDouble("lng"), fix.getLatitude(), fix.getLongitude(), d);
        float slack = Math.max(SAME_PLACE_M, Math.max(fix.getAccuracy(), (float) e.optDouble("acc", 0)));
        // Never tighter than the fix's own accuracy, so a vague cell-based fix doesn't look like a move.
        return d[0] <= Math.min(slack, Math.max(SAME_PLACE_MAX_M, fix.getAccuracy()));
    }

    /** Moves an entry's end to {@code now}; a more accurate {@code fix} also refines its position. */
    private static void extend(Context c, JSONObject e, long now, Location fix, String wifi) {
        try {
            e.put("to", now).put("u", now);
            if (wifi != null) e.put("w", wifi);
            if (fix != null) {
                e.put("f", now);
                if (fix.hasAccuracy() && fix.getAccuracy() < e.optDouble("acc", Double.MAX_VALUE)) {
                    e.put("lat", fix.getLatitude()).put("lng", fix.getLongitude()).put("acc", Math.round(fix.getAccuracy()));
                }
            }
            Battery.put(c, e);
        } catch (Exception ex) {
            Log.w(Policy.TAG, "track extend: " + ex);
        }
    }

    private static JSONObject entry(Context c, long now, Location fix, String wifi) {
        JSONObject e = new JSONObject();
        try {
            e.put("t", now).put("to", now).put("u", now).put("f", now);
            e.put("lat", fix.getLatitude()).put("lng", fix.getLongitude());
            if (fix.hasAccuracy()) e.put("acc", Math.round(fix.getAccuracy()));
            if (wifi != null) e.put("w", wifi);
            Battery.put(c, e);
        } catch (Exception ex) {
            Log.w(Policy.TAG, "track entry: " + ex);
        }
        return e;
    }

    /** The connected Wi-Fi network's BSSID, or null when not on Wi-Fi (or it's hidden from the app). */
    @SuppressWarnings("deprecation")
    private static String bssid(Context c) {
        try {
            WifiInfo info = c.getSystemService(WifiManager.class).getConnectionInfo();
            String b = info == null ? null : info.getBSSID();
            return b == null || b.equals("02:00:00:00:00:00") ? null : b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- sending ----------

    /**
     * Sends entries changed since the last successful send, in batches, advancing the cursor only when the
     * hub accepts a batch. With {@code heartbeat}, a message goes out even with nothing new, carrying the
     * battery level. Blocks; call from the outbox worker.
     */
    static void flush(Context c, long timeoutMs, boolean heartbeat) {
        while (true) {
            long sent = Policy.prefs(c).getLong("track_sent", 0);
            JSONArray batch = new JSONArray();
            long newest = sent;
            synchronized (LocationLog.class) {
                JSONArray log = load(c);
                for (int i = 0; i < log.length() && batch.length() < BATCH; i++) {
                    JSONObject e = log.optJSONObject(i);
                    if (e == null || e.optLong("u") <= sent) continue;
                    batch.put(forParents(e));
                    newest = Math.max(newest, e.optLong("u"));
                }
            }
            // App use since the last send travels with the readings (see AppUsage).
            long appsSent = Policy.prefs(c).getLong("usage_sent", System.currentTimeMillis() - EVERY_MS);
            JSONArray apps = AppUsage.sessionsSince(c, appsSent);
            if (batch.length() == 0 && apps.length() == 0 && !heartbeat) return;
            try {
                JSONObject o = Remote.msg("track");
                o.put("points", batch);
                if (apps.length() > 0) o.put("apps", apps);
                Battery.put(c, o);
                if (!Remote.publishNow(c, o, timeoutMs)) return;
            } catch (Exception e) {
                return;
            }
            Policy.prefs(c).edit().putLong("track_sent", newest)
                    .putLong("usage_sent", AppUsage.newestEnd(apps, appsSent)).commit();
            heartbeat = false;
            if (batch.length() < BATCH) return;
        }
    }

    /** Replies to a parent's {@code track} request with every entry still running at or after {@code since}. */
    static void sendHistory(Context c, long since, String req) {
        new Thread(() -> {
            try {
                JSONArray out = new JSONArray();
                synchronized (LocationLog.class) {
                    JSONArray log = load(c);
                    for (int i = 0; i < log.length(); i++) {
                        JSONObject e = log.optJSONObject(i);
                        if (e != null && e.optLong("to") >= since) out.put(forParents(e));
                    }
                }
                JSONObject o = Remote.msg("track");
                o.put("req", req);
                o.put("points", out);
                JSONArray apps = AppUsage.sessionsSince(c, since);
                if (apps.length() > 0) o.put("apps", apps);
                Battery.put(c, o);
                Remote.publish(c, o, null);
            } catch (Exception e) {
                Log.w(Policy.TAG, "track history: " + e);
            }
        }, "track").start();
    }

    /**
     * Where the device last was, for the parents' last-gasp notices (low battery, turning off):
     * {@code lat}, {@code lng}, {@code at} (ms, last seen there), {@code acc}; null if the log is empty.
     */
    static JSONObject lastPlace(Context c) {
        try {
            JSONArray log;
            synchronized (LocationLog.class) {
                log = load(c);
            }
            JSONObject e = log.length() == 0 ? null : log.optJSONObject(log.length() - 1);
            if (e == null || !e.has("lat")) return null;
            return new JSONObject().put("lat", e.getDouble("lat")).put("lng", e.getDouble("lng"))
                    .put("at", e.optLong("to")).put("acc", e.optInt("acc"));
        } catch (Exception ex) {
            return null;
        }
    }

    /** An entry without the device-only fields. */
    private static JSONObject forParents(JSONObject e) {
        JSONObject o = new JSONObject();
        try {
            for (String k : new String[]{"t", "to", "lat", "lng", "acc", "batt", "chg"}) {
                if (e.has(k)) o.put(k, e.get(k));
            }
        } catch (Exception ignored) {}
        return o;
    }

    /** For diagnostics: "42 entries, last reading 12 min ago, last tick 3 min ago, 0 waiting to send". */
    static String summary(Context c) {
        long now = System.currentTimeMillis();
        long sent = Policy.prefs(c).getLong("track_sent", 0);
        long tick = Policy.prefs(c).getLong("track_tick", 0);
        int unsent = 0;
        long lastTo = 0;
        int count;
        synchronized (LocationLog.class) {
            JSONArray log = load(c);
            count = log.length();
            for (int i = 0; i < count; i++) {
                JSONObject e = log.optJSONObject(i);
                if (e == null) continue;
                if (e.optLong("u") > sent) unsent++;
                lastTo = Math.max(lastTo, e.optLong("to"));
            }
        }
        return count + " entries"
                + (lastTo > 0 ? ", last reading " + Diagnostics.ago(now - lastTo) + " ago" : "")
                + (tick > 0 ? ", last tick " + Diagnostics.ago(now - tick) + " ago" : ", no tick yet")
                + ", " + unsent + " waiting to send";
    }

    // ---------- storage ----------

    private static File file(Context c) {
        return new File(c.getFilesDir(), "track.json");
    }

    private static JSONArray load(Context c) {
        try {
            File f = file(c);
            if (!f.isFile()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(Policy.TAG, "track load: " + e);
            return new JSONArray();
        }
    }

    /** Writes the log through a temp file, so a crash mid-write can't lose it. */
    private static void save(Context c, JSONArray log) {
        try {
            File tmp = new File(c.getFilesDir(), "track.json.tmp");
            Files.write(tmp.toPath(), log.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file(c))) Log.w(Policy.TAG, "track save: rename failed");
        } catch (Exception e) {
            Log.w(Policy.TAG, "track save: " + e);
        }
    }

    /** Drops entries that ended more than 7 days ago, and the oldest beyond the size cap. */
    private static JSONArray prune(JSONArray log, long now) {
        JSONArray out = new JSONArray();
        int skip = Math.max(0, log.length() - MAX_ENTRIES);
        for (int i = skip; i < log.length(); i++) {
            JSONObject e = log.optJSONObject(i);
            if (e != null && now - e.optLong("to") <= KEEP_MS) out.put(e);
        }
        return out;
    }
}
