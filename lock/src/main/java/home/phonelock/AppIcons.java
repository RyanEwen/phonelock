package home.phonelock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sends app icons to the parent apps, which can't read the child's installed apps. Parents ask only for
 * icons they don't have cached, so this runs once per app, not on every refresh.
 */
final class AppIcons {
    /** Edge length of each icon, in pixels; enough for a 40 dp chip icon on a dense parent phone. */
    private static final int SIZE = 96;
    /** At most this many per request, to keep one attachment small. */
    private static final int MAX_APPS = 60;

    private AppIcons() {}

    /**
     * Replies to {@code icons} request {@code req} with an {@code icons} message whose encrypted attachment is
     * JSON {@code {pkg: base64 WebP}}. Unknown packages are skipped.
     */
    static void send(Context c, String req, JSONArray apps) {
        if (apps == null) return;
        new Thread(() -> {
            try {
                JSONObject icons = new JSONObject();
                PackageManager pm = c.getPackageManager();
                for (int i = 0; i < apps.length() && i < MAX_APPS; i++) {
                    String pkg = apps.optString(i);
                    try {
                        Drawable d = pm.getApplicationIcon(
                                pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES));
                        icons.put(pkg, Base64.encodeToString(encode(d), Base64.NO_WRAP));
                    } catch (PackageManager.NameNotFoundException ignored) {}
                }
                JSONObject o = Remote.msg("icons");
                o.put("req", req);
                Remote.publishWithBlob(c, o, icons.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(Policy.TAG, "icons: " + e);
            }
        }, "icons").start();
    }

    /** Renders {@code d} at {@link #SIZE} px and compresses it as lossy WebP. */
    private static byte[] encode(Drawable d) {
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        d.setBounds(0, 0, SIZE, SIZE);
        d.draw(canvas);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out);
        b.recycle();
        return out.toByteArray();
    }
}
