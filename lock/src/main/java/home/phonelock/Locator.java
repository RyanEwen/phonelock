package home.phonelock;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
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
        if (pickProvider(lm) == null) {
            reply(c, req, null, "No location provider on the device");
            return;
        }
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            reply(c, req, null, "The device has no location permission");
            return;
        }
        fix(c, true, loc -> reply(c, req, loc, loc == null ? "No location fix yet" : null));
    }

    /**
     * Blocking form of {@link #fix} for background work (the location log): the fix, or null if there is
     * none. Waits at most the fix timeout; never call on the main thread.
     */
    static Location fixNow(Context c, boolean precise) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Location[] out = new Location[1];
        fix(c, precise, loc -> {
            out[0] = loc;
            done.countDown();
        });
        try {
            done.await(TIMEOUT_MS + 5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return out[0];
    }

    /**
     * Gets a fresh fix and hands it to {@code done} on the main thread exactly once: the fix, else the last
     * known one if no fresh fix arrives in time, else null (location off, no provider, no permission).
     * {@code precise} is a parent tapping "Location" (the system's default request); otherwise a balanced,
     * lower-power reading is enough (the 15-minute log), which the fused provider gets from Wi-Fi and cell.
     */
    static void fix(Context c, boolean precise, java.util.function.Consumer<Location> done) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        String provider = lm.isLocationEnabled() ? pickProvider(lm) : null;
        if (provider == null) {
            done.accept(null);
            return;
        }

        // getCurrentLocation reports at most once; the timeout path covers a provider that never answers.
        AtomicBoolean answered = new AtomicBoolean(false);
        CancellationSignal cancel = new CancellationSignal();
        Handler main = new Handler(Looper.getMainLooper());
        Runnable timeout = () -> {
            if (!answered.compareAndSet(false, true)) return;
            cancel.cancel();
            done.accept(lastKnown(lm));
        };
        main.postDelayed(timeout, TIMEOUT_MS);

        java.util.function.Consumer<Location> got = loc -> {
            if (!answered.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout);
            done.accept(loc != null ? loc : lastKnown(lm));
        };
        try {
            if (precise) {
                // The parent's "Location" button: unchanged from before (the system's default request).
                lm.getCurrentLocation(provider, cancel, c.getMainExecutor(), got);
            } else {
                LocationRequest request = new LocationRequest.Builder(0)
                        .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                        .setDurationMillis(TIMEOUT_MS)
                        .build();
                lm.getCurrentLocation(provider, request, cancel, c.getMainExecutor(), got);
            }
        } catch (SecurityException e) {
            if (!answered.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout);
            done.accept(null);
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
            Battery.put(c, o);
            Remote.publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "loc reply: " + e);
        }
    }
}
