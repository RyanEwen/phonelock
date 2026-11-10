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
import java.util.Set;
import java.util.TreeSet;

/**
 * Listens to a private ntfy topic for parent commands ("allow whatsapp 2h", "revoke all", "status")
 * and expires temporary grants on time. Replies are posted back to the same topic prefixed "phone:".
 */
public class RemoteService extends Service {
    static final String REPLY_PREFIX = "phone:";
    private volatile boolean running;
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
        nm.createNotificationChannel(new NotificationChannel("lock", "Phone lock",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "lock")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Parent controls active")
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
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

    /** Re-apply policy whenever the set of live grants changes (i.e. one expired). */
    private void expiryLoop() {
        Set<String> last = new TreeSet<>(Grants.load(this).keySet());
        while (running) {
            try { Thread.sleep(20_000); } catch (InterruptedException e) { if (!running) return; }
            Set<String> now = new TreeSet<>(Grants.load(this).keySet());
            if (!now.equals(last)) {
                Set<String> gone = new TreeSet<>(last);
                gone.removeAll(now);
                Policy.apply(this);
                if (!gone.isEmpty()) reply("time's up for " + gone + ". " + Grants.describe(this, Grants.load(this)));
                last = now;
            }
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
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    backoff = 2_000;
                    String line;
                    while (running && (line = r.readLine()) != null) handle(line);
                }
            } catch (Exception e) {
                Log.w(Policy.TAG, "ntfy: " + e);
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
            // Ignore commands older than 6 hours (phone was off a long time).
            long sent = o.optLong("time", 0) * 1000;
            if (sent > 0 && System.currentTimeMillis() - sent > 6 * 3600_000L) return;
            String out = Grants.command(this, msg);
            Log.i(Policy.TAG, "remote '" + msg + "' -> " + out);
            if (out != null) reply(out);
        } catch (Exception e) {
            Log.w(Policy.TAG, "bad ntfy line: " + e);
        }
    }

    void reply(String text) {
        String topic = topic(this);
        if (topic.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(server(this) + "/" + topic).openConnection();
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
