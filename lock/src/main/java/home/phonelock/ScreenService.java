package home.phonelock;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Takes a screenshot when a parent asks to see the screen. An accessibility service is the only way to
 * capture without the child approving each time (MediaProjection asks on every session). It is enabled
 * once over adb (see tools/configure-lock.sh) and the device owner policy keeps any other service out.
 *
 * It listens to no accessibility events; it exists only for {@link #takeScreenshot}.
 */
public class ScreenService extends AccessibilityService {
    /** Longest edge of the image sent to parents, in pixels. Keeps a shot around 100 KB. */
    private static final int MAX_EDGE = 1280;
    private static final int JPEG_QUALITY = 70;

    /** Fixed id so repeated views update one notice instead of stacking. */
    private static final int NOTICE_ID = 0x5c4ee7;
    private static final long LOG_EVERY_MS = 5 * 60_000L;

    private static volatile ScreenService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    /**
     * Captures the screen for request {@code req} from parent {@code who} and publishes it as an encrypted
     * ntfy attachment with a sealed {@code shot} message. Failures are published as {@code shot} with an
     * {@code error}. The child is then told who looked (posted after the capture, so the notice isn't in
     * the picture) and the view is logged for the parents.
     */
    static void capture(Context c, String req, String who) {
        ScreenService s = instance;
        if (s == null) {
            reply(c, req, null, "Screen view isn't switched on. On the device, unlock parent mode and turn on Settings > Accessibility > Parent screen view", null);
            return;
        }
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (!pm.isInteractive()) {
            reply(c, req, null, "The screen is off", null);
            return;
        }
        String app = s.foregroundPackage();
        s.takeScreenshot(Display.DEFAULT_DISPLAY, c.getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                // Compression is slow-ish; keep it off the main thread.
                new Thread(() -> {
                    try {
                        byte[] jpeg = encode(r.getHardwareBuffer(), r.getColorSpace());
                        reply(c, req, jpeg, null, app);
                        recordView(c, who);
                    } catch (Exception e) {
                        Log.w(Policy.TAG, "shot encode: " + e);
                        reply(c, req, null, "Couldn't capture the screen", app);
                    }
                }, "shot").start();
            }

            @Override public void onFailure(int code) {
                reply(c, req, null, failure(code, app == null ? null : Remote.label(c, app)), app);
            }
        });
    }

    /**
     * Records that {@code who} looked at the screen: a notice for the child (one notification updated in
     * place), and an entry in the parents' activity log at most once per parent every {@link #LOG_EVERY_MS},
     * so auto-refresh doesn't flood either side.
     */
    private static void recordView(Context c, String who) {
        Remote.notifyKid(c, who + " viewed your screen", NOTICE_ID);
        android.content.SharedPreferences p = Policy.prefs(c);
        String key = "shot_logged_" + who;
        long now = System.currentTimeMillis();
        if (now - p.getLong(key, 0) < LOG_EVERY_MS) return;
        p.edit().putLong(key, now).apply();
        Remote.event(c, who + " viewed the screen", who, false);
    }

    /** Closes the notification shade if it's open (so the ring screen is visible). No-op if not enabled. */
    /**
     * Opens Android's own power menu (Power off, Restart, Emergency), as holding the power button does.
     * False if the service isn't running (screen view switched off).
     */
    static boolean showPowerMenu() {
        ScreenService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static void dismissShade() {
        ScreenService s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
    }

    /** Package of the app in the active window, or null. */
    private String foregroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Downscales the capture to {@link #MAX_EDGE} and returns it as JPEG bytes. */
    private static byte[] encode(HardwareBuffer hb, ColorSpace cs) {
        try {
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
            if (hw == null) throw new IllegalStateException("no bitmap");
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            float k = Math.min(1f, MAX_EDGE / (float) Math.max(sw.getWidth(), sw.getHeight()));
            Bitmap small = k < 1f
                    ? Bitmap.createScaledBitmap(sw, Math.round(sw.getWidth() * k), Math.round(sw.getHeight() * k), true)
                    : sw;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            if (small != sw) small.recycle();
            sw.recycle();
            return out.toByteArray();
        } finally {
            hb.close();
        }
    }

    private static String failure(int code, String appLabel) {
        switch (code) {
            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                return (appLabel == null ? "The app on screen" : appLabel) + " blocks screenshots";
            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "Too soon after the last screenshot; try again";
            default:
                return "Couldn't capture the screen (error " + code + ")";
        }
    }

    /** Publishes the {@code shot} reply, with the encrypted image attached when {@code jpeg} is set. */
    private static void reply(Context c, String req, byte[] jpeg, String error, String app) {
        try {
            JSONObject o = Remote.msg("shot");
            o.put("req", req);
            if (app != null) {
                o.put("app", app);
                o.put("label", Remote.label(c, app));
            }
            if (error != null) o.put("error", error);
            if (jpeg == null) Remote.publish(c, o, null);
            else Remote.publishWithBlob(c, o, jpeg);
        } catch (Exception e) {
            Log.w(Policy.TAG, "shot reply: " + e);
        }
    }
}
