package home.phonelock;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Keeps the lock app up to date by itself, from the public GitHub release that setup also installs from.
 * A small version file ({versionCode, sha256, size}) is checked a few times a day and on a parent's
 * "Update now"; a newer build is downloaded and verified (package name, newer version, checksum, and the
 * same signing key as this app), then installed silently, as device owner, the next time the screen is off
 * and no call or ring is in progress. The app restarts during the install; {@link #reportIfUpdated} then
 * tells the parents.
 */
final class Updater {
    static final String RELEASE = "https://github.com/RyanEwen/phonelock/releases/latest/download/";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final long MAX_APK = 30L * 1024 * 1024;
    private static final long RETRY_MS = 3600_000L;

    private Updater() {}

    static int installedVersion(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Version downloaded, verified and waiting for the screen to turn off; 0 if none. */
    static int readyVersion(Context c) {
        int v = Policy.prefs(c).getInt("update_ready", 0);
        return v > installedVersion(c) && apk(c).isFile() ? v : 0;
    }

    private static File apk(Context c) {
        return new File(c.getFilesDir(), "update.apk");
    }

    /** Whether the periodic check is due (cheap; the service's timer asks before starting a thread). */
    static boolean checkDue(Context c) {
        return System.currentTimeMillis() - Policy.prefs(c).getLong("update_checked", 0) >= CHECK_EVERY_MS;
    }

    /**
     * Looks for a newer release and downloads it. {@code who} is the parent who asked ("Update now"),
     * or null for the periodic check (which reports only when something new is ready). Blocks; call off the
     * main thread.
     */
    static synchronized void check(Context c, String who) {
        android.content.SharedPreferences prefs = Policy.prefs(c);
        try {
            JSONObject v = new JSONObject(new String(fetch(RELEASE + "lock-version.json", 64 * 1024), StandardCharsets.UTF_8));
            // Only a check that reached the release counts; a failed one is retried within the hour.
            prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply();
            int latest = v.getInt("versionCode");
            int installed = installedVersion(c);
            if (latest <= installed) {
                if (who != null) Remote.event(c, "Already up to date (version " + installed + ")", who, false);
                return;
            }
            boolean ready = readyVersion(c) == latest;
            if (!ready && who == null) {
                // The periodic check doesn't spend her mobile data, and doesn't retry a version that already
                // failed to install; a parent's "Update now" does both.
                if (isMetered(c)) return;
                if (prefs.getInt("update_failed", 0) == latest) return;
            }
            if (!ready) download(c, latest, v.getString("sha256"));
            // The periodic check reports only a new download; "Update now" always answers.
            if (!ready || who != null) {
                String txt = "Version " + latest + " downloaded; it installs the next time the screen is off";
                Remote.event(c, txt, who, who == null);
                Remote.sendState(c, false);
            }
            installIfIdle(c);
        } catch (Exception e) {
            Log.w(Policy.TAG, "update check: " + e);
            prefs.edit().putLong("update_checked", System.currentTimeMillis() - CHECK_EVERY_MS + RETRY_MS).apply();
            if (who != null) Remote.event(c, "Couldn't check for an update: " + e.getMessage(), who, false);
        }
    }

    /** Whether the current network costs her data (mobile, or a metered hotspot). */
    private static boolean isMetered(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return true;
        }
    }

    /** Forgets a downloaded update that couldn't be installed, so it isn't retried on every screen-off. */
    private static void discard(Context c, int version) {
        Policy.prefs(c).edit().remove("update_ready").putInt("update_failed", version).apply();
        apk(c).delete();
    }

    /** Downloads the APK for {@code version} and keeps it only if every check passes. */
    private static void download(Context c, int version, String sha256) throws Exception {
        File tmp = new File(c.getFilesDir(), "update.part");
        HttpURLConnection h = open(RELEASE + "lock-release.apk");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_APK) throw new Exception("Update is too large");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } finally {
            h.disconnect();
        }
        try {
            if (!toHex(md.digest()).equalsIgnoreCase(sha256)) throw new Exception("Update checksum doesn't match");
            verifyPackage(c, tmp, version);
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(apk(c))) throw new Exception("Couldn't store the update");
        Policy.prefs(c).edit().putInt("update_ready", version).apply();
    }

    /** The APK must be this app, the advertised newer version, and signed with the same key. */
    private static void verifyPackage(Context c, File f, int version) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null || !c.getPackageName().equals(pi.packageName)) throw new Exception("Update isn't this app");
        if (pi.getLongVersionCode() != version) throw new Exception("Update version doesn't match");
        PackageInfo mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi.signingInfo == null || mine.signingInfo == null
                || !Arrays.equals(signers(pi), signers(mine))) {
            throw new Exception("Update isn't signed with this app's key");
        }
    }

    private static Signature[] signers(PackageInfo pi) {
        return pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners()
                : pi.signingInfo.getSigningCertificateHistory();
    }

    /** Installs a ready update if the screen is off and nothing is ringing or in a call. */
    static void installIfIdle(Context c) {
        int ready = readyVersion(c);
        if (ready == 0) return;
        PowerManager pm = c.getSystemService(PowerManager.class);
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        boolean inCall = false;
        try {
            inCall = tm != null && tm.isInCall();
        } catch (SecurityException ignored) {}
        if (pm.isInteractive() || inCall || Ringer.isRinging()) return;
        install(c, ready);
    }

    /**
     * Installs the verified APK silently. The app is replaced and restarted mid-way, so success is
     * reported by {@link #reportIfUpdated} after the restart; {@link Result} handles failures.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLING = new java.util.concurrent.atomic.AtomicBoolean();

    private static void install(Context c, int version) {
        // One install at a time: screen-off and a finished download can both get here.
        if (!INSTALLING.compareAndSet(false, true)) return;
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                File f = apk(c);
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("update.apk", 0, f.length())) {
                    in.transferTo(out);
                    s.fsync(out);
                }
                // commit(), not apply(): the install kills this process moments later.
                Policy.prefs(c).edit().putInt("update_from", installedVersion(c)).commit();
                Intent result = new Intent(c, Result.class);
                PendingIntent cb = PendingIntent.getBroadcast(c, 7, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(cb.getIntentSender());
            }
            Log.i(Policy.TAG, "installing update " + version);
        } catch (Exception e) {
            Log.w(Policy.TAG, "update install: " + e);
            Remote.event(c, "Couldn't install update " + version + ": " + e.getMessage(), null, true);
            discard(c, version);
            INSTALLING.set(false);
        }
    }

    /** After a restart from an update: tell the parents and clean up. Called on MY_PACKAGE_REPLACED. */
    static void reportIfUpdated(Context c) {
        int from = Policy.prefs(c).getInt("update_from", 0);
        int now = installedVersion(c);
        Policy.prefs(c).edit().remove("update_from").remove("update_ready").remove("update_failed").apply();
        apk(c).delete();
        if (from > 0 && now > from) Remote.event(c, "Updated to version " + now, null, true);
    }

    /** Install outcome. A pending user action is never shown (she could answer it); it's a failure. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            INSTALLING.set(false);
            if (status == PackageInstaller.STATUS_SUCCESS) return; // reported after the restart
            String msg = status == PackageInstaller.STATUS_PENDING_USER_ACTION
                    ? "the device wanted to ask first" : i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            // Drop the download so it isn't retried at every screen-off; "Update now" fetches it again.
            int ready = Policy.prefs(c).getInt("update_ready", 0);
            if (ready > 0) discard(c, ready);
            Remote.event(c, "Couldn't install the update: " + msg, null, false);
        }
    }

    // ---------- helpers ----------

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(20_000);
        h.setReadTimeout(60_000);
        return h;
    }

    private static byte[] fetch(String url, int max) throws Exception {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            return in.readNBytes(max);
        } finally {
            h.disconnect();
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
