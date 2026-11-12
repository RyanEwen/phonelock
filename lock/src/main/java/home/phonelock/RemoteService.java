package home.phonelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Listens to a private ntfy topic for parent commands ("allow whatsapp 2h", "revoke all", "status")
 * and expires temporary grants on time. Replies are posted back to the same topic prefixed "phone:".
 */
public class RemoteService extends Service {
    static final String REPLY_PREFIX = "phone:";
    private volatile boolean running;
    private android.content.BroadcastReceiver screenOff, battery, shutdown;
    /** Receives location fixes other apps request, for the location log (costs no battery itself). */
    private android.location.LocationListener passive;
    private Thread listener, timer;
    private volatile HttpURLConnection conn;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, RemoteService.class));
        } catch (Exception e) {
            Log.w(Policy.TAG, "start service: " + e);
        }
    }

    static void restart(Context c) {
        c.stopService(new Intent(c, RemoteService.class));
        start(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("lock", "Parent controls",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "lock")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Parent controls active")
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        getMainExecutor().execute(() -> Ringer.recover(this));
        CallLogFeed.watch(this);
        ScreenViewGuard.watch(this);
        BuiltInApps.addOnce(this);
        if (screenOff == null) {
            // A downloaded update installs when the screen turns off, never while she's using the device.
            screenOff = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    new Thread(() -> Updater.installIfIdle(c), "update").start();
                }
            };
            registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_EXPORTED);
        }
        if (battery == null) {
            // Low-battery alert and the "Turning off" notice (see Battery); both need a registered receiver.
            battery = Battery.watch(this);
            shutdown = Battery.watchShutdown(this);
        }
        if (passive == null) {
            passive = loc -> new Thread(() -> LocationLog.recordPassive(this, loc), "passive").start();
            try {
                getSystemService(android.location.LocationManager.class).requestLocationUpdates(
                        android.location.LocationManager.PASSIVE_PROVIDER, 60_000, 50f, passive, android.os.Looper.getMainLooper());
            } catch (SecurityException e) {
                Log.w(Policy.TAG, "passive location: " + e);
                passive = null;
            }
        }
        // The 15-minute location log, and anything still waiting to be sent from before.
        LocationLog.schedule(this);
        Outbox.flush(this);
        if (!running) {
            running = true;
            listener = new Thread(this::listenLoop, "ntfy");
            listener.start();
            timer = new Thread(this::expiryLoop, "expiry");
            timer.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (screenOff != null) {
            unregisterReceiver(screenOff);
            screenOff = null;
        }
        if (passive != null) {
            getSystemService(android.location.LocationManager.class).removeUpdates(passive);
            passive = null;
        }
        if (battery != null) {
            unregisterReceiver(battery);
            unregisterReceiver(shutdown);
            battery = null;
            shutdown = null;
        }
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
        if (listener != null) listener.interrupt();
        if (timer != null) timer.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static String server(Context c) {
        return Policy.prefs(c).getString("ntfy_server", "https://ntfy.sh");
    }

    static String topic(Context c) {
        return Policy.prefs(c).getString("ntfy_topic", "");
    }

    /**
     * Re-apply policy whenever the set of live grants changes (one expired) or the schedule's situation
     * changes (e.g. bedtime starts), and tell the child and the parents.
     */
    private void expiryLoop() {
        java.util.Map<String, Long> lastGrants = Grants.load(this);
        java.util.Map<String, Long> lastBlocks = Grants.loadBlocks(this);
        int lastMode = Schedule.currentMode(this);
        while (running) {
            try { Thread.sleep(20_000); } catch (InterruptedException e) { if (!running) return; }
            // A few times a day, look for a lock app update (on its own thread: a download can take a while).
            if (Updater.checkDue(this)) new Thread(() -> Updater.check(this, null), "update").start();
            Debugging.expireIfDue(this);
            AppTime.tick(this, false);
            int mode = Schedule.currentMode(this);
            if (mode != lastMode) {
                lastMode = mode;
                onModeChanged(mode);
            }

            java.util.Map<String, Long> blocks = Grants.loadBlocks(this);
            Set<String> blocksEnded = expired(lastBlocks, blocks);
            boolean blocksChanged = !blocks.keySet().equals(lastBlocks.keySet());
            lastBlocks = blocks;
            if (!blocksEnded.isEmpty()) onBlocksEnded(blocksEnded);
            else if (blocksChanged) Policy.apply(this);

            java.util.Map<String, Long> grants = Grants.load(this);
            Set<String> grantsEnded = expired(lastGrants, grants);
            boolean grantsChanged = !grants.keySet().equals(lastGrants.keySet());
            lastGrants = grants;
            if (grantsChanged) Policy.apply(this);
            if (!grantsEnded.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (String p : grantsEnded) names.add(Remote.label(this, p));
                String txt = "Time's up: " + String.join(", ", names);
                if (Remote.paired(this)) {
                    Remote.notifyKid(this, txt);
                    Remote.event(this, txt);
                    Remote.sendState(this, false);
                } else {
                    replyPlain(this, txt + ". " + Grants.describe(this, grants));
                }
            }
        }
    }

    /**
     * Entries of {@code before} that are gone from {@code after} because their time ran out. Ones a parent
     * removed early (revoke, unblock, or a grant replacing a block) still had time left and are not
     * reported, so nobody gets a false "Time's up" or a second "is back".
     */
    private static Set<String> expired(java.util.Map<String, Long> before, java.util.Map<String, Long> after) {
        long now = System.currentTimeMillis();
        Set<String> out = new TreeSet<>();
        for (java.util.Map.Entry<String, Long> e : before.entrySet()) {
            boolean gone = !after.containsKey(e.getKey());
            if (gone && e.getValue() <= now) out.add(e.getKey());
        }
        return out;
    }

    /** Temporary blocks ran out: put the apps back and tell the child; parents just get a list entry. */
    private void onBlocksEnded(Set<String> ended) {
        Policy.apply(this);
        List<String> names = new ArrayList<>();
        for (String p : ended) names.add(Remote.label(this, p));
        String txt = String.join(", ", names) + (ended.size() == 1 ? " is" : " are") + " back";
        Remote.notifyKid(this, txt);
        if (Remote.paired(this)) {
            Remote.event(this, txt, null, true);
            Remote.sendState(this, false);
        }
    }

    /** A schedule boundary passed: re-lock and report it. */
    private void onModeChanged(int mode) {
        // Apps that are no longer allowed get hidden, which closes them; the home screen re-reads on its tick.
        Policy.apply(this);
        long until = Schedule.currentModeUntil(this);
        String txt = (mode == Schedule.NORMAL ? "Normal hours" : Schedule.modeLabel(mode))
                + (until > 0 ? " until " + Remote.time(this, until) : "");
        if (Remote.paired(this)) {
            Remote.event(this, txt, null, true); // routine: activity list only
            Remote.sendState(this, false);
        }
    }

    private void listenLoop() {
        long backoff = 2_000;
        while (running) {
            String topic = topic(this);
            if (topic.isEmpty()) {
                sleep(60_000);
                continue;
            }
            try {
                String since = Policy.prefs(this).getString("ntfy_last_id", null);
                if (since == null) since = String.valueOf(System.currentTimeMillis() / 1000);
                URL u = new URL(server(this) + "/" + topic + "/json?since=" + since);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                conn = c;
                c.setConnectTimeout(20_000);
                c.setReadTimeout(120_000); // ntfy sends keepalives every ~45 s
                // Ask for an uncompressed stream so proxies (Cloudflare) pass each line on immediately.
                c.setRequestProperty("Accept-Encoding", "identity");
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    backoff = 2_000;
                    Policy.prefs(this).edit().putLong("hub_connected_at", System.currentTimeMillis())
                            .remove("hub_error").apply();
                    // Connected again: send what was kept while offline (alerts, location readings).
                    Outbox.flush(this);
                    String line;
                    while (running && (line = r.readLine()) != null) handle(line);
                }
            } catch (Exception e) {
                Log.w(Policy.TAG, "ntfy: " + e);
                // Kept for diagnostics: why the device last lost (or couldn't get) the hub connection.
                Policy.prefs(this).edit().putString("hub_error", e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 120_000);
        }
    }

    private void handle(String line) {
        try {
            JSONObject o = new JSONObject(line);
            if (!"message".equals(o.optString("event"))) return;
            String id = o.optString("id");
            Policy.prefs(this).edit().putString("ntfy_last_id", id).apply();
            String msg = o.optString("message", "").trim();
            if (msg.isEmpty() || msg.startsWith(REPLY_PREFIX)) return;
            // Ignore anything older than a day (the hub's cache); Remote applies tighter limits per type.
            long sent = o.optLong("time", 0) * 1000;
            if (sent > 0 && System.currentTimeMillis() - sent > 24 * 3600_000L) return;
            JSONObject att = o.optJSONObject("attachment");
            String url = att == null ? null : att.optString("url", null);
            Remote.handle(this, msg, url);
        } catch (Exception e) {
            Log.w(Policy.TAG, "bad ntfy line: " + e);
        }
    }

    /** Legacy plain-text reply (only used before pairing). */
    static void replyPlain(Context ctx, String text) {
        String topic = topic(ctx);
        if (topic.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(server(ctx) + "/" + topic).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("Title", "Phone lock");
                c.setRequestProperty("Tags", "iphone");
                try (OutputStream os = c.getOutputStream()) {
                    os.write((REPLY_PREFIX + " " + text).getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                Log.w(Policy.TAG, "reply: " + e);
            }
        }).start();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
