package home.phonelock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sends the device's call log to the parents: new calls as they are logged, and the recent history on
 * request. Needs READ_CALL_LOG, which Android restricts; the device owner grants it where allowed and
 * tools/configure-lock.sh grants it over adb otherwise. Without it, the feed stays silent.
 */
final class CallLogFeed {
    /** Most entries sent in one reply. */
    private static final int MAX_ENTRIES = 200;
    private static ContentObserver observer;

    private CallLogFeed() {}

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts watching the call log (once per process); each change sends the calls not sent yet. */
    static synchronized void watch(Context c) {
        if (observer != null || !permitted(c)) return;
        Context app = c.getApplicationContext();
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                new Thread(() -> sendNew(app), "calllog").start();
            }
        };
        app.getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer);
    }

    /** Sends calls logged after the last one sent, as a {@code calls} message. */
    private static synchronized void sendNew(Context c) {
        long last = Policy.prefs(c).getLong("calllog_sent", System.currentTimeMillis() - 60_000);
        JSONArray entries = read(c, last, MAX_ENTRIES);
        if (entries.length() == 0) return;
        Policy.prefs(c).edit().putLong("calllog_sent", entries.optJSONObject(0).optLong("ts", last)).apply();
        publish(c, entries, null);
    }

    /** Replies to a {@code calllog} request with up to {@link #MAX_ENTRIES} recent calls. */
    static void sendHistory(Context c, String req) {
        new Thread(() -> publish(c, read(c, 0, MAX_ENTRIES), req), "calllog").start();
    }

    private static void publish(Context c, JSONArray entries, String req) {
        try {
            JSONObject o = Remote.msg("calls");
            if (req != null) o.put("req", req);
            o.put("entries", entries);
            if (!permitted(c)) o.put("error", "The device hasn't allowed reading its call log");
            Remote.publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "calls: " + e);
        }
    }

    /** Calls newer than {@code after} (ms), newest first: {ts, number, name, type, dur (s)}. */
    private static JSONArray read(Context c, long after, int limit) {
        JSONArray out = new JSONArray();
        if (!permitted(c)) return out;
        String[] cols = {CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DURATION};
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols,
                CallLog.Calls.DATE + " > ?", new String[]{String.valueOf(after)},
                CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext() && out.length() < limit) {
                String number = cur.getString(1);
                String name = cur.getString(2);
                out.put(new JSONObject()
                        .put("ts", cur.getLong(0))
                        .put("number", number == null ? "" : number)
                        .put("name", name != null && !name.isEmpty() ? name : CallGuard.describe(c, number))
                        .put("type", typeName(cur.getInt(3)))
                        .put("dur", cur.getLong(4)));
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "read call log: " + e);
        }
        return out;
    }

    private static String typeName(int t) {
        switch (t) {
            case CallLog.Calls.INCOMING_TYPE: return "in";
            case CallLog.Calls.OUTGOING_TYPE: return "out";
            case CallLog.Calls.MISSED_TYPE: return "missed";
            case CallLog.Calls.REJECTED_TYPE: return "rejected";
            case CallLog.Calls.BLOCKED_TYPE: return "blocked";
            case CallLog.Calls.VOICEMAIL_TYPE: return "voicemail";
            default: return "other";
        }
    }
}
