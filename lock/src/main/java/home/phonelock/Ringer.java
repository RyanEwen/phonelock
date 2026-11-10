package home.phonelock;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Rings the phone loudly so a parent can get the child's attention or find a lost phone.
 * Plays the alarm sound on the alarm stream at full volume (which ignores silent and vibrate mode),
 * vibrates, and shows {@link RingActivity} with a Stop button. Stops by itself after the requested time,
 * restoring the alarm volume and Do Not Disturb setting it changed.
 *
 * All methods must be called on the main thread.
 */
final class Ringer {
    static final int DEFAULT_SECONDS = 120;
    static final int MAX_SECONDS = 300;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static int savedVolume = -1;
    private static int savedFilter = -1;
    private static String caller = "";
    /** Application context captured by {@link #start}, for the auto-stop timer. */
    private static Context appCtx;
    private static final Runnable AUTO_STOP = () -> stop(null, "Ringing stopped after the time ran out");

    private Ringer() {}

    static boolean isRinging() {
        return player != null;
    }

    /** Name of the parent who started the current ring, for the ring screen. */
    static String caller() {
        return caller;
    }

    /**
     * Starts ringing for {@code seconds} (clamped to 10..{@link #MAX_SECONDS}), or extends the current ring.
     * {@code who} is the parent's name shown on the ring screen.
     */
    static void start(Context c, String who, int seconds) {
        Context app = c.getApplicationContext();
        appCtx = app;
        caller = who;
        MAIN.removeCallbacks(AUTO_STOP);
        MAIN.postDelayed(AUTO_STOP, Math.max(10, Math.min(MAX_SECONDS, seconds)) * 1000L);
        showScreen(app);
        if (player != null) return; // already ringing: just extended

        AudioManager am = app.getSystemService(AudioManager.class);
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        liftTotalSilence(app);

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(app, alarmSound());
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.w(Policy.TAG, "ring: " + e);
        }

        Vibrator v = app.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
        Remote.sendState(app, false);
    }

    /**
     * Stops ringing and restores the audio settings. {@code eventText}, if not null, is reported to the
     * parents (e.g. who stopped it). Does nothing if not ringing.
     */
    static void stop(Context c, String eventText) {
        MAIN.removeCallbacks(AUTO_STOP);
        MediaPlayer mp = player;
        if (mp == null) return;
        player = null;
        try {
            mp.stop();
        } catch (Exception ignored) {}
        mp.release();

        Context app = c != null ? c.getApplicationContext() : appCtx;
        Vibrator v = app.getSystemService(Vibrator.class);
        if (v != null) v.cancel();
        AudioManager am = app.getSystemService(AudioManager.class);
        if (savedVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
        savedVolume = -1;
        restoreFilter(app);

        app.sendBroadcast(new Intent(RingActivity.ACTION_CLOSE).setPackage(app.getPackageName()));
        if (eventText != null) Remote.event(app, eventText);
        Remote.sendState(app, false);
    }

    private static void showScreen(Context app) {
        ScreenService.dismissShade();
        app.startActivity(new Intent(app, RingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        return u;
    }

    /**
     * "Total silence" Do Not Disturb mutes even alarms. If adb granted us DND access
     * ({@code cmd notification allow_dnd home.phonelock}), relax it to alarms-only while ringing.
     */
    private static void liftTotalSilence(Context app) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) return;
        int f = nm.getCurrentInterruptionFilter();
        if (f == NotificationManager.INTERRUPTION_FILTER_NONE) {
            savedFilter = f;
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
        }
    }

    private static void restoreFilter(Context app) {
        if (savedFilter < 0) return;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(savedFilter);
        savedFilter = -1;
    }
}
