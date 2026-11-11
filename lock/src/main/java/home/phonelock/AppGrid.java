package home.phonelock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A non-scrolling grid of app icons with labels, used for the home screen's sections and the
 * "Ask for an app" picker. It sizes to its content, so several grids can share one scroll view.
 */
final class AppGrid extends LinearLayout {
    /** Fewest columns (phones in portrait); wider screens such as tablets fit more. */
    static final int MIN_COLUMNS = 4;
    /** Width each icon cell needs, in dp, when working out how many fit. */
    private static final int CELL_DP = 96;

    /** One app cell. {@code note}, if set, is a second line under the label (e.g. "until 6:22"). */
    static final class Entry {
        final String pkg;
        final CharSequence label;
        final Drawable icon;
        String note;

        Entry(String pkg, CharSequence label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    interface OnPick {
        void pick(Entry e);
    }

    AppGrid(Context c) {
        super(c);
        setOrientation(VERTICAL);
    }

    /**
     * Loads entries for {@code pkgs} in order, skipping packages that aren't installed or, when
     * {@code launchableOnly}, have no launcher activity.
     */
    static List<Entry> load(Context c, List<String> pkgs, boolean launchableOnly) {
        PackageManager pm = c.getPackageManager();
        List<Entry> out = new ArrayList<>();
        for (String p : pkgs) {
            if (launchableOnly && pm.getLaunchIntentForPackage(p) == null) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                out.add(new Entry(p, pm.getApplicationLabel(ai), pm.getApplicationIcon(ai)));
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return out;
    }

    /** Replaces the grid's contents; tapping a cell calls {@code onPick}. */
    void setApps(List<Entry> entries, OnPick onPick) {
        removeAllViews();
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = Math.max(MIN_COLUMNS, Math.min(8, widthDp / CELL_DP));
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                LayoutParams rl = new LayoutParams(-1, -2);
                if (i > 0) rl.topMargin = dp(20);
                addView(row, rl);
            }
            Entry e = entries.get(i);
            LinearLayout cell = cell(e);
            cell.setOnClickListener(v -> onPick.pick(e));
            row.addView(cell, new LayoutParams(0, -2, 1));
        }
        // Pad the last row so its cells keep the same width as the rows above.
        if (row != null) {
            for (int k = entries.size() % columns; k != 0 && k < columns; k++) {
                row.addView(new android.view.View(getContext()), new LayoutParams(0, 1, 1));
            }
        }
    }

    private LinearLayout cell(Entry e) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setBackground(touchFeedback());
        cell.setPadding(0, dp(8), 0, dp(8));

        ImageView iv = new ImageView(getContext());
        iv.setImageDrawable(e.icon);
        cell.addView(iv, new LayoutParams(dp(56), dp(56)));

        LayoutParams tl = new LayoutParams(-1, -2);
        tl.topMargin = dp(6);
        cell.addView(text(e.label, 12, Color.WHITE), tl);
        if (e.note != null) cell.addView(text(e.note, 11, Color.rgb(170, 176, 190)), new LayoutParams(-1, -2));
        return cell;
    }

    /** A soft white ripple in a rounded square, like current launchers, instead of the old yellow highlight. */
    private android.graphics.drawable.RippleDrawable touchFeedback() {
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(20));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(48, 255, 255, 255)), null, mask);
    }

    private TextView text(CharSequence s, int sp, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
