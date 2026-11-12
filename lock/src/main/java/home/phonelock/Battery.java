package home.phonelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONObject;

/**
 * Battery reporting for the parents: the level and charging state go out with every state and location
 * message, a "Battery low" alert fires once when the level drops to the parents' threshold, and a shutdown
 * notice is sent while the device turns off, so a dead battery can be told apart from lost signal.
 */
final class Battery {
    /** Alert threshold used until a parent picks one, in percent. */
    static final int DEFAULT_ALERT = 15;
    /** Levels above threshold + this re-arm the alert, so hovering around it doesn't alert repeatedly. */
    private static final int REARM_MARGIN = 5;
    /** How long a shutdown may hold up to get its notice out. */
    private static final long SHUTDOWN_SEND_MS = 4_000;
    /** Then how long sending the last unsent location readings may take (Android waits about 10 s in all). */
    private static final long SHUTDOWN_FLUSH_MS = 3_000;

    private Battery() {}

    /** The sticky battery broadcast, or null if the system has none yet. */
    private static Intent sticky(Context c) {
        return c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** Battery level in percent from a battery broadcast, or -1 if unknown. */
    private static int level(Intent b) {
        if (b == null) return -1;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level >= 0 && scale > 0 ? level * 100 / scale : -1;
    }

    /** Whether a battery broadcast says it is plugged in and charging (or full). */
    private static boolean charging(Intent b) {
        if (b == null) return false;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    /** Current level in percent, or -1 if unknown. */
    static int level(Context c) {
        return level(sticky(c));
    }

    /** Whether it is plugged in and charging (or full). */
    static boolean charging(Context c) {
        return charging(sticky(c));
    }

    /**
     * The level at or below which the battery counts as low: the parents' alert level, or the default
     * when they turned the alert off (she should still be told to charge).
     */
    static int lowAt(Context c) {
        int at = alertAt(c);
        return at > 0 ? at : DEFAULT_ALERT;
    }

    /** Adds {@code batt} (percent) and {@code chg} (charging) to an outgoing message. */
    static void put(Context c, JSONObject o) throws Exception {
        Intent b = sticky(c);
        int level = level(b);
        if (level >= 0) o.put("batt", level);
        o.put("chg", charging(b));
    }

    /** The parents' low-battery threshold in percent; 0 means no alert. */
    static int alertAt(Context c) {
        return Policy.prefs(c).getInt("batt_alert", DEFAULT_ALERT);
    }

    /** Sets the low-battery threshold (0 turns the alert off) and re-arms it. */
    static void setAlertAt(Context c, int percent) {
        int p = Math.max(0, Math.min(50, percent));
        Policy.prefs(c).edit().putInt("batt_alert", p).putBoolean("batt_alerted", false).apply();
    }

    /**
     * Watches the level while the service runs. ACTION_BATTERY_CHANGED can only be received by a
     * registered receiver, which the service keeps for its lifetime.
     */
    static BroadcastReceiver watch(Context c) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                onChanged(ctx, level(i), charging(i));
            }
        };
        c.registerReceiver(r, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
        return r;
    }

    /**
     * Fires the low-battery alert once per discharge: when the level reaches the threshold while not
     * charging. The latch is persisted so a service restart doesn't alert again; charging or climbing
     * clearly above the threshold re-arms it.
     */
    private static void onChanged(Context c, int level, boolean charging) {
        if (level < 0) return;
        int at = alertAt(c);
        android.content.SharedPreferences p = Policy.prefs(c);
        boolean alerted = p.getBoolean("batt_alerted", false);

        if (charging || level > at + REARM_MARGIN) {
            if (alerted) p.edit().putBoolean("batt_alerted", false).apply();
            return;
        }
        if (at == 0 || alerted || level > at) return;

        p.edit().putBoolean("batt_alerted", true).apply();
        // Where she last was goes with it, in case the phone dies before the parents look.
        Outbox.alert(c, "Battery low: " + level + "%", LocationLog.lastPlace(c));
    }

    /**
     * Receives the system's shutdown broadcast and sends a notice before the network goes. Best effort:
     * a battery that dies abruptly, or no signal at that moment, means no notice.
     */
    static BroadcastReceiver watchShutdown(Context c) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                PendingResult pending = goAsync();
                new Thread(() -> {
                    try {
                        sendShutdown(ctx);
                    } finally {
                        pending.finish();
                    }
                }, "shutdown").start();
            }
        };
        c.registerReceiver(r, new IntentFilter(Intent.ACTION_SHUTDOWN), Context.RECEIVER_EXPORTED);
        return r;
    }

    /** Sends "Turning off", saying so when the battery is the reason; waits only briefly. */
    private static void sendShutdown(Context c) {
        try {
            int level = level(c);
            String text = level >= 0 && level <= 2 && !charging(sticky(c))
                    ? "Turning off: the battery is empty"
                    : "Turning off" + (level >= 0 ? " (battery " + level + "%)" : "");
            JSONObject o = Remote.msg("event");
            o.put("text", text);
            // Tells parents' apps the silence that follows is the phone being off, not lost signal.
            o.put("off", true);
            JSONObject where = LocationLog.lastPlace(c);
            if (where != null) o.put("where", where);
            // The notice first (it matters most), then any readings not sent yet, in the time left.
            Remote.publishNow(c, o, SHUTDOWN_SEND_MS);
            LocationLog.flush(c, SHUTDOWN_FLUSH_MS, false);
        } catch (Exception e) {
            Log.w(Policy.TAG, "shutdown notice: " + e);
        }
    }
}
