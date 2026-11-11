package home.phonelock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private AppGrid homeGrid, allowedGrid;
    private TextView allowedHeading;
    /** "Bedtime until 7:00 AM" under the date; hidden in normal hours. */
    private TextView modeText;
    /** Situation the screen was last built for, so the tick refreshes when it changes. */
    private int shownMode = -1;
    private LinearLayout parentBar;
    private TextView parentText;
    private boolean receiversOn;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Policy.apply(c);
            refresh();
        }
    };

    /** Policy was re-applied (grant, schedule, app changes): redraw. */
    private final BroadcastReceiver policyChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
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
            // Pick up schedule changes (bedtime starting) while the home screen is showing.
            int mode = Schedule.currentMode(HomeActivity.this);
            if (mode != shownMode) {
                shownMode = mode;
                refresh();
            }
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
        // Deep blue-grey to near black, top to bottom: calm, and icons stand out against it.
        root.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(28, 34, 52), Color.rgb(12, 13, 18)}));
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

        modeText = new TextView(this);
        modeText.setTextColor(Color.rgb(255, 214, 150));
        modeText.setTextSize(15);
        modeText.setBackground(rounded(Color.argb(60, 255, 190, 90), 50));
        modeText.setPadding(dp(16), dp(6), dp(16), dp(6));
        modeText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.gravity = Gravity.CENTER_HORIZONTAL;
        ml.topMargin = dp(12);
        root.addView(modeText, ml);

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

        // Home apps, then (when there are any) the apps a parent has allowed for now, in one scroll view.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        homeGrid = new AppGrid(this);
        sections.addView(homeGrid, new LinearLayout.LayoutParams(-1, -2));
        allowedHeading = new TextView(this);
        allowedHeading.setText("Allowed for now");
        allowedHeading.setTextColor(Color.rgb(170, 176, 190));
        allowedHeading.setTextSize(14);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(36);
        hl.bottomMargin = dp(12);
        hl.leftMargin = dp(8);
        sections.addView(allowedHeading, hl);
        allowedGrid = new AppGrid(this);
        sections.addView(allowedGrid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(sections, new android.widget.FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1);
        gl.topMargin = dp(32);
        root.addView(scroll, gl);

        Button ask = button("Ask for an app", v -> askForApp());
        ask.setTextColor(Color.WHITE);
        ask.setBackground(rounded(Color.rgb(52, 60, 84), 28));
        ask.setTextSize(16);
        ask.setStateListAnimator(null);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, dp(56));
        al.setMargins(dp(8), dp(8), dp(8), dp(4));
        root.addView(ask, al);

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
        // Icons, not a text list: easier to find the app you mean.
        AppGrid grid = new AppGrid(this);
        int pad = dp(16);
        grid.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(grid);
        AlertDialog pick = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Which app?")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        List<AppGrid.Entry> choices = AppGrid.load(this, pkgs, false);
        choices.sort((x, y) -> x.label.toString().compareToIgnoreCase(y.label.toString()));
        grid.setApps(choices, e -> {
            pick.dismiss();
            askHowLong(e);
        });
        pick.show();
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
            registerReceiver(policyChanged, new IntentFilter(Policy.ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            receiversOn = true;
        }
        Policy.apply(this);
        RemoteService.start(this);
        enforceLockTask();
        refresh();
        h.removeCallbacks(tick);
        h.post(tick);
        // Pressing Home doesn't dismiss a parent's ring; only its Stop button does.
        if (Ringer.isRinging()) startActivity(new Intent(this, RingActivity.class));
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
            unregisterReceiver(policyChanged);
        }
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { /* home screen: nowhere to go back to */ }

    /** Rotation or a tablet's window change: re-flow the icon grids for the new width. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        refresh();
    }

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

    /** Second step of "Ask for an app": how long, then send the request to the parents. */
    private void askHowLong(AppGrid.Entry app) {
        String[] times = {"30 minutes", "1 hour", "2 hours", "4 hours"};
        long[] mins = {30, 60, 120, 240};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label + " for how long?")
                .setItems(times, (d, w) -> {
                    boolean ok = Remote.request(this, app.pkg, mins[w]);
                    Toast.makeText(this, ok ? "Asked. You'll get a notification."
                            : "Wait a minute before asking again", Toast.LENGTH_LONG).show();
                })
                .show();
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

    /**
     * Rebuilds the screen: the situation line, the scheduled apps allowed now, and apps a parent allowed for a
     * while (with their end time).
     */
    private void refresh() {
        int mode = Schedule.currentMode(this);
        long until = Schedule.currentModeUntil(this);
        if (mode == Schedule.NORMAL) {
            modeText.setVisibility(View.GONE);
        } else {
            modeText.setText(Schedule.modeLabel(mode) + (until > 0 ? " until " + Remote.time(this, until) : ""));
            modeText.setVisibility(View.VISIBLE);
        }

        List<String> scheduled = Schedule.allowedNow(this);
        homeGrid.setApps(AppGrid.load(this, scheduled, true), e -> launch(e.pkg));

        java.util.Map<String, Long> grants = Grants.load(this);
        List<String> extra = new ArrayList<>();
        for (String p : grants.keySet()) if (!scheduled.contains(p)) extra.add(p);
        List<AppGrid.Entry> allowed = AppGrid.load(this, extra, true);
        for (AppGrid.Entry e : allowed) e.note = "until " + Remote.time(this, grants.get(e.pkg));
        allowedGrid.setApps(allowed, e -> launch(e.pkg));
        int vis = allowed.isEmpty() ? View.GONE : View.VISIBLE;
        allowedHeading.setVisibility(vis);
        allowedGrid.setVisibility(vis);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** A rounded rectangle of {@code color} with corner radius {@code radiusDp}. */
    private android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
