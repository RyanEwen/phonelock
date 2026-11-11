package home.phonelock;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.Arrays;
import java.util.List;

/**
 * Wi-Fi and Bluetooth, offered on the child's home screen as if they were apps: they have a name and an icon,
 * and parents schedule, hide, allow and pause them like any other app. They aren't installed packages but
 * screens of this app ({@link ConnectionsActivity}), so the places that deal with real packages (hiding,
 * suspending, the lock's allowed list, uninstalling) skip them; everything that works on app names
 * (schedules, grants, blocks, the Ask list) treats them like apps.
 *
 * <p>Their ids look like package names so they fit the same lists; no real package uses them.
 */
final class BuiltInApps {
    static final String WIFI = "home.phonelock.wifi";
    static final String BLUETOOTH = "home.phonelock.bluetooth";
    static final List<String> ALL = Arrays.asList(WIFI, BLUETOOTH);

    private BuiltInApps() {}

    static boolean is(String id) {
        return WIFI.equals(id) || BLUETOOTH.equals(id);
    }

    /** "Wi-Fi settings" / "Bluetooth settings": what she can change there, not the radio itself. */
    static String label(String id) {
        return BLUETOOTH.equals(id) ? "Bluetooth settings" : "Wi-Fi settings";
    }

    /** Opens its screen. */
    static Intent launch(Context c, String id) {
        return new Intent(c, ConnectionsActivity.class)
                .putExtra(ConnectionsActivity.EXTRA_TAB,
                        BLUETOOTH.equals(id) ? ConnectionsActivity.TAB_BLUETOOTH : ConnectionsActivity.TAB_WIFI);
    }

    /** A round launcher-style icon: a coloured circle with a white symbol. */
    static Drawable icon(Context c, String id) {
        int size = Math.round(96 * c.getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(BLUETOOTH.equals(id) ? Color.rgb(41, 98, 255) : Color.rgb(0, 150, 136));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        if (BLUETOOTH.equals(id)) {
            // The Bluetooth rune.
            p.setTextSize(size * 0.55f);
            p.setFakeBoldText(true);
            canvas.drawText("ᛒ", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p);
        } else {
            // Three Wi-Fi arcs and a dot.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size * 0.07f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float cx = size / 2f;
            float cy = size * 0.68f;
            for (int i = 1; i <= 3; i++) {
                float r = size * 0.12f * i;
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 225, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, size * 0.05f, p);
        }
        return new BitmapDrawable(c.getResources(), b);
    }

    /**
     * Once per device: puts Wi-Fi and Bluetooth on the home screen (normal and limited hours, like a newly
     * added app), since they used to be always available there.
     */
    static void addOnce(Context c) {
        android.content.SharedPreferences p = Policy.prefs(c);
        if (p.getBoolean("built_in_apps_added", false)) return;
        java.util.List<String> apps = new java.util.ArrayList<>(Policy.visibleApps(c));
        for (String id : ALL) if (!apps.contains(id)) apps.add(id);
        p.edit().putString("apps", String.join(",", apps)).putBoolean("built_in_apps_added", true).apply();
    }
}
