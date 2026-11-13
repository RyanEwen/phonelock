package home.phonelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tells the parents about changes on the device that cut it off or get around the lock, which nothing else
 * reports: airplane mode, mobile data switched off or on, the SIM card removed or swapped, and an app being
 * installed (parent mode now opens every app, the Play Store included). Started with the remote service,
 * which runs all the time; each change is queued in the {@link Outbox} so it still goes out once the
 * device is back online.
 */
final class DeviceWatch {
    private static BroadcastReceiver airplane;
    private static BroadcastReceiver installs;
    private static ContentObserver mobileData;
    private static SubscriptionManager.OnSubscriptionsChangedListener sims;

    /** An "added" app installed longer ago than this is a hidden app being shown again, not an install. */
    private static final long NEW_INSTALL_MS = 5 * 60_000L;

    private DeviceWatch() {}

    /** Starts watching (once per process; later calls do nothing). */
    static synchronized void start(Context c) {
        Context app = c.getApplicationContext();
        if (airplane != null) return;

        // Airplane mode: on means she drops off the map, so it notifies; off is just noted.
        airplane = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                boolean on = i.getBooleanExtra("state", false);
                if (on) Outbox.alert(ctx, "Airplane mode turned on (no calls or location updates until it's off)");
                else Outbox.note(ctx, "Airplane mode turned off");
            }
        };
        app.registerReceiver(airplane, new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), Context.RECEIVER_EXPORTED);

        // Mobile data on or off (a global setting; there's no broadcast for it).
        mobileData = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                boolean on = Settings.Global.getInt(app.getContentResolver(), "mobile_data", 1) == 1;
                if (on == Policy.prefs(app).getBoolean("watch_mobile_data", true)) return;
                Policy.prefs(app).edit().putBoolean("watch_mobile_data", on).apply();
                Outbox.note(app, on ? "Mobile data turned on" : "Mobile data turned off (only Wi-Fi from now on)");
            }
        };
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor("mobile_data"), false, mobileData);

        // SIM cards: compared with the last ones seen, so a reboot with the same SIM says nothing.
        try {
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            sims = new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override public void onSubscriptionsChanged() {
                    checkSims(app);
                }
            };
            sm.addOnSubscriptionsChangedListener(app.getMainExecutor(), sims);
        } catch (Exception e) {
            Log.w(Policy.TAG, "sim watch: " + e);
        }

        // New apps (not updates of installed ones).
        installs = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) || i.getData() == null) return;
                String pkg = i.getData().getSchemeSpecificPart();
                // Showing a hidden app (parent mode shows them all) also arrives as "added": only report an
                // app whose install is actually new.
                try {
                    long installed = ctx.getPackageManager().getPackageInfo(pkg, 0).firstInstallTime;
                    if (System.currentTimeMillis() - installed > NEW_INSTALL_MS) return;
                } catch (Exception e) {
                    return;
                }
                String when = Policy.inParentMode(ctx) ? " during parent mode" : "";
                Outbox.alert(ctx, "Installed " + Remote.label(ctx, pkg) + when);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addDataScheme("package");
        app.registerReceiver(installs, f, Context.RECEIVER_EXPORTED);
    }

    /** Stops watching (the service is going away). */
    static synchronized void stop(Context c) {
        Context app = c.getApplicationContext();
        if (airplane == null) return;
        try {
            app.unregisterReceiver(airplane);
            app.unregisterReceiver(installs);
            app.getContentResolver().unregisterContentObserver(mobileData);
            if (sims != null) app.getSystemService(SubscriptionManager.class).removeOnSubscriptionsChangedListener(sims);
        } catch (Exception e) {
            Log.w(Policy.TAG, "device watch stop: " + e);
        }
        airplane = null;
        installs = null;
        mobileData = null;
        sims = null;
    }

    /**
     * Compares the SIM cards in the phone with the last ones seen (by carrier and card id) and reports a
     * removal or a different card. The first check after setup only remembers them.
     */
    static void checkSims(Context c) {
        String now;
        String names;
        try {
            List<SubscriptionInfo> list = c.getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            List<String> keys = new ArrayList<>();
            List<String> carriers = new ArrayList<>();
            if (list != null) {
                for (SubscriptionInfo s : list) {
                    keys.add(s.getCardId() + ":" + s.getCarrierId() + ":" + s.getSubscriptionId());
                    carriers.add(String.valueOf(s.getCarrierName()));
                }
            }
            Collections.sort(keys);
            now = String.join(",", keys);
            names = String.join(", ", carriers);
        } catch (Exception e) {
            Log.w(Policy.TAG, "sim check: " + e);
            return;
        }
        android.content.SharedPreferences p = Policy.prefs(c);
        String before = p.getString("watch_sims", null);
        if (now.equals(before)) return;
        p.edit().putString("watch_sims", now).apply();
        if (before == null) return;
        if (now.isEmpty()) Outbox.alert(c, "SIM card removed (no calls or mobile data)");
        else if (before.isEmpty()) Outbox.alert(c, "SIM card inserted (" + names + ")");
        else Outbox.alert(c, "SIM card changed (now " + names + ")");
    }
}
