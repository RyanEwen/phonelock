package home.phonelock;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answers a parent's "find" request with the phone's current position and battery.
 * Uses only the framework LocationManager: the fused provider (backed by Play services on this phone)
 * when present, else GPS, falling back to the last known fix if no fresh one arrives in time.
 */
final class Locator {
    /** How long to wait for a fresh fix before settling for the last known one. */
    private static final long TIMEOUT_MS = 30_000;

    private Locator() {}

    /**
     * Gets a fix and publishes a {@code loc} reply for request {@code req}. Never throws; failures are
     * reported to the parents as {@code loc} with an {@code error}.
     */
    static void locate(Context c, String req) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (!lm.isLocationEnabled()) {
            reply(c, req, null, "Location is turned off on the device");
            return;
        }
        String provider = pickProvider(lm);
        if (provider == null) {
            reply(c, req, null, "No location provider on the device");
            return;
        }

        // getCurrentLocation reports at most once; the timeout path covers a provider that never answers.
        AtomicBoolean done = new AtomicBoolean(false);
        CancellationSignal cancel = new CancellationSignal();
        Handler main = new Handler(Looper.getMainLooper());
        Runnable timeout = () -> {
            if (!done.compareAndSet(false, true)) return;
            cancel.cancel();
            Location last = lastKnown(lm);
            reply(c, req, last, last == null ? "No location fix yet" : null);
        };
        main.postDelayed(timeout, TIMEOUT_MS);

        try {
            lm.getCurrentLocation(provider, cancel, c.getMainExecutor(), loc -> {
                if (!done.compareAndSet(false, true)) return;
                main.removeCallbacks(timeout);
                Location best = loc != null ? loc : lastKnown(lm);
                reply(c, req, best, best == null ? "No location fix yet" : null);
            });
        } catch (SecurityException e) {
            done.set(true);
            main.removeCallbacks(timeout);
            reply(c, req, null, "The device has no location permission");
        }
    }

    /** Prefer the fused provider, which blends Wi-Fi, cell and GPS and works indoors. */
    private static String pickProvider(LocationManager lm) {
        if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) return LocationManager.FUSED_PROVIDER;
        if (lm.hasProvider(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        if (lm.hasProvider(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        return null;
    }

    /** Most recent cached fix from any provider, or null. */
    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {}
        }
        return best;
    }

    /** Publishes the {@code loc} reply. {@code loc} may be null when {@code error} is set. */
    private static void reply(Context c, String req, Location loc, String error) {
        try {
            JSONObject o = Remote.msg("loc");
            o.put("req", req);
            if (loc != null) {
                o.put("lat", loc.getLatitude());
                o.put("lng", loc.getLongitude());
                if (loc.hasAccuracy()) o.put("acc", Math.round(loc.getAccuracy()));
                o.put("fix", loc.getTime());
                o.put("prov", loc.getProvider());
            }
            if (error != null) o.put("error", error);
            putBattery(c, o);
            Remote.publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "loc reply: " + e);
        }
    }

    /** Adds {@code batt} (percent) and {@code chg} (charging) from the sticky battery broadcast. */
    static void putBattery(Context c, JSONObject o) throws Exception {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) o.put("batt", level * 100 / scale);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        o.put("chg", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
    }
}
