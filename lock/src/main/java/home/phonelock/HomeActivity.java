package home.phonelock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>();
    private AppAdapter adapter;
    private LinearLayout parentBar;
    private TextView parentText;
    private boolean receiversOn;

    private static final class App {
        String pkg;
        CharSequence label;
        Drawable icon;
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Policy.apply(c);
            refresh();
        }
    };

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Policy.inParentMode(c)) endParentMode();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!Policy.inParentMode(HomeActivity.this) && parentBar.getVisibility() == View.VISIBLE) {
                endParentMode();
            } else {
                updateParentBar();
            }
            h.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 18, 24));
        root.setPadding(dp(16), dp(40), dp(16), dp(16));
        root.setFitsSystemWindows(true);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(64);
        clock.setGravity(Gravity.CENTER);
        root.addView(clock, new LinearLayout.LayoutParams(-1, -2));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextColor(Color.rgb(170, 176, 190));
        date.setTextSize(16);
        date.setGravity(Gravity.CENTER);
        root.addView(date, new LinearLayout.LayoutParams(-1, -2));

        // Parent unlock: press and hold the clock for 4 seconds.
        clock.setOnTouchListener(new View.OnTouchListener() {
            final Runnable fire = () -> askPin();
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: h.postDelayed(fire, 4000); return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: h.removeCallbacks(fire); return true;
                }
                return true;
            }
        });

        parentBar = new LinearLayout(this);
        parentBar.setOrientation(LinearLayout.VERTICAL);
        parentBar.setBackgroundColor(Color.rgb(90, 40, 20));
        parentBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        parentText = new TextView(this);
        parentText.setTextColor(Color.WHITE);
        parentBar.addView(parentText);
        LinearLayout btns = new LinearLayout(this);
        btns.addView(button("Settings", v -> launch("com.android.settings")));
        btns.addView(button("Play Store", v -> launch("com.android.vending")));
        btns.addView(button("Lock now", v -> endParentMode()));
        parentBar.addView(btns);
        parentBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(-1, -2);
        pbl.topMargin = dp(12);
        root.addView(parentBar, pbl);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(20));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos).pkg));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1);
        gl.topMargin = dp(32);
        root.addView(grid, gl);

        Button ask = button("Ask for an app", v -> askForApp());
        ask.setTextColor(Color.WHITE);
        ask.setBackgroundColor(Color.rgb(40, 46, 60));
        root.addView(ask, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    /** Kid picks an app and a duration; parents get a request on their companion apps. */
    private void askForApp() {
        if (!Remote.paired(this)) {
            Toast.makeText(this, "Not set up yet", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> pkgs = Remote.otherApps(this);
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "Nothing to ask for", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Remote.label(this, pkgs.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Which app?")
                .setItems(labels, (d, which) -> {
                    String[] times = {"30 minutes", "1 hour", "2 hours", "4 hours"};
                    long[] mins = {30, 60, 120, 240};
                    new AlertDialog.Builder(this)
                            .setTitle("For how long?")
                            .setItems(times, (d2, w2) -> {
                                boolean ok = Remote.request(this, pkgs.get(which), mins[w2]);
                                Toast.makeText(this, ok ? "Asked. You'll get a notification."
                                        : "Wait a minute before asking again", Toast.LENGTH_LONG).show();
                            })
                            .show();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiversOn) {
            IntentFilter pf = new IntentFilter();
            pf.addAction(Intent.ACTION_PACKAGE_ADDED);
            pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pf.addDataScheme("package");
            registerReceiver(pkgReceiver, pf, Context.RECEIVER_EXPORTED);
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    Context.RECEIVER_EXPORTED);
            receiversOn = true;
        }
        Policy.apply(this);
        RemoteService.start(this);
        enforceLockTask();
        refresh();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        enforceLockTask();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (receiversOn) {
            unregisterReceiver(pkgReceiver);
            unregisterReceiver(screenOff);
        }
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { /* home screen: nowhere to go back to */ }

    private void enforceLockTask() {
        if (!Policy.isOwner(this)) return;
        ActivityManager am = getSystemService(ActivityManager.class);
        boolean locked = am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        if (Policy.inParentMode(this)) {
            if (locked) {
                try { stopLockTask(); } catch (Exception ignored) {}
            }
        } else if (!locked && Policy.dpm(this).isLockTaskPermitted(getPackageName())) {
            try { startLockTask(); } catch (Exception ignored) {}
        }
        updateParentBar();
    }

    private void updateParentBar() {
        boolean p = Policy.inParentMode(this);
        parentBar.setVisibility(p ? View.VISIBLE : View.GONE);
        if (p) {
            long until = Policy.prefs(this).getLong("parent_until", 0);
            parentText.setText("Parent mode. Locks again at "
                    + DateFormat.getTimeFormat(this).format(new java.util.Date(until))
                    + " or when the screen turns off.");
        }
    }

    private void endParentMode() {
        Policy.prefs(this).edit().putLong("parent_until", 0).apply();
        Policy.apply(this);
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        enforceLockTask();
        refresh();
    }

    private void askPin() {
        if (!Policy.hasPin(this)) {
            Toast.makeText(this, "No PIN set", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("Parent PIN")
                .setView(in)
                .setPositiveButton("Unlock", (d, w) -> {
                    long r = Policy.checkPin(this, in.getText().toString());
                    if (r == 0) {
                        Policy.prefs(this).edit()
                                .putLong("parent_until", System.currentTimeMillis() + 15 * 60_000L).apply();
                        Policy.apply(this);
                        enforceLockTask();
                    } else if (r > 0) {
                        Toast.makeText(this, "Try again in " + r + " s", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Blocked", Toast.LENGTH_SHORT).show();
        }
    }

    private void refresh() {
        PackageManager pm = getPackageManager();
        apps.clear();
        for (String p : Policy.homeApps(this)) {
            if (pm.getLaunchIntentForPackage(p) == null) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                App a = new App();
                a.pkg = p;
                a.label = pm.getApplicationLabel(ai);
                a.icon = pm.getApplicationIcon(ai);
                apps.add(a);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        adapter.notifyDataSetChanged();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout cell = new LinearLayout(HomeActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(HomeActivity.this);
            iv.setImageDrawable(apps.get(i).icon);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView tv = new TextView(HomeActivity.this);
            tv.setText(apps.get(i).label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = dp(6);
            cell.addView(tv, tl);
            return cell;
        }
    }
}
