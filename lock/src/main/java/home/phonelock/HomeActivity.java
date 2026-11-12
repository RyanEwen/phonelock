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
    private AppGrid homeGrid, allowedGrid, pausedGrid;
    private TextView allowedHeading, pausedHeading;
    /**
     * Wi-Fi and Bluetooth: not on the home grid but behind the ⋮ button next to "Ask for an app", and only
     * while allowed (by schedule or for a while). The menu always ends with Power, for turning the device
     * off or restarting it. (Settings, when allowed, is an ordinary app on the grid.)
     */
    private static final List<String> QUICK = BuiltInApps.ALL;
    private TextView quickButton;
    private final List<String> quickAllowed = new ArrayList<>();
    /** "Bedtime until 7:00 AM" under the date; hidden in normal hours. */
    private TextView modeText;
    /** "Battery low (12%). Plug in to charge." under the date; hidden unless low and not charging. */
    private TextView batteryText;
    /** Situation the screen was last built for, so the tick refreshes when it changes. */
    private int shownMode = -1;
    private LinearLayout parentBar;
    private TextView parentText;
    private boolean receiversOn;

    /**
     * An app was installed, removed or changed: re-apply in the background (the home screen redraws when
     * that finishes). Changes right after an apply are its own hiding and showing of apps, and are ignored so
     * they can't set off another round.
     */
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Policy.justApplied()) return;
            Policy.applySoon(c);
        }
    };

    /** Policy was re-applied (grant, schedule, app changes): redraw. */
    private final BroadcastReceiver policyChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            refresh();
        }
    };

    /** Battery level or charging changed: show or hide the low-battery notice. */
    private final BroadcastReceiver batteryChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateBattery();
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

        // Said plainly on her own screen: her parents can see these.
        TextView shared = new TextView(this);
        shared.setText("Location, battery, calls and app use are shared with your parents");
        shared.setTextColor(Color.rgb(120, 126, 140));
        shared.setTextSize(12);
        shared.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(4);
        root.addView(shared, sl);

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

        // Same pill shape as the situation above, in red, so a low battery is hard to miss.
        batteryText = new TextView(this);
        batteryText.setTextColor(Color.rgb(255, 190, 185));
        batteryText.setTextSize(15);
        batteryText.setBackground(rounded(Color.argb(90, 220, 60, 50), 50));
        batteryText.setPadding(dp(16), dp(6), dp(16), dp(6));
        batteryText.setGravity(Gravity.CENTER);
        batteryText.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.gravity = Gravity.CENTER_HORIZONTAL;
        bl.topMargin = dp(8);
        root.addView(batteryText, bl);

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

        // Home apps, then (when there are any) apps a parent allowed or paused for now, in one scroll view.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        homeGrid = new AppGrid(this);
        sections.addView(homeGrid, new LinearLayout.LayoutParams(-1, -2));
        allowedHeading = sectionHeading(sections, "Allowed for now");
        allowedGrid = new AppGrid(this);
        sections.addView(allowedGrid, new LinearLayout.LayoutParams(-1, -2));
        pausedHeading = sectionHeading(sections, "Paused for now");
        pausedGrid = new AppGrid(this);
        sections.addView(pausedGrid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(sections, new android.widget.FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1);
        gl.topMargin = dp(32);
        root.addView(scroll, gl);

        Button ask = button("Ask for an app", v -> askForApp());
        ask.setTextColor(Color.WHITE);
        ask.setBackground(rounded(Color.rgb(52, 60, 84), 28));
        ask.setTextSize(16);
        ask.setStateListAnimator(null);
        // Ask, with the ⋮ button (Wi-Fi, Bluetooth, Power) beside it.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(ask, new LinearLayout.LayoutParams(0, dp(56), 1));
        quickButton = new TextView(this);
        quickButton.setText("⋮");
        quickButton.setTextSize(26);
        quickButton.setTextColor(Color.WHITE);
        quickButton.setGravity(Gravity.CENTER);
        quickButton.setBackground(rounded(Color.rgb(52, 60, 84), 28));
        quickButton.setContentDescription("Wi-Fi, Bluetooth and power");
        quickButton.setOnClickListener(v -> showQuickMenu());
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(56), dp(56));
        ql.leftMargin = dp(10);
        bottom.addView(quickButton, ql);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.setMargins(dp(8), dp(8), dp(8), dp(4));
        root.addView(bottom, al);



        setContentView(root);
    }

    /** Kid picks an app and a duration; parents get a request on their companion apps. */
    private void askForApp() {
        if (!Remote.paired(this)) {
            Toast.makeText(this, "Not set up yet", Toast.LENGTH_SHORT).show();
            return;
        }
        // Anything she can't use right now: unscheduled apps, and her own apps that are outside their hours
        // or paused (e.g. Phone at bedtime), so she can ask for those too.
        List<String> pkgs = new ArrayList<>(Remote.otherApps(this));
        // Apps a parent hid from the list are never offered, whatever their schedule says.
        List<String> usable = Policy.homeApps(this);
        java.util.Set<String> hidden = Policy.notOffered(this);
        for (String p : Schedule.masks(this).keySet()) {
            if (!usable.contains(p) && !pkgs.contains(p) && !hidden.contains(p) && Policy.isPresent(this, p)) pkgs.add(p);
        }
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "Nothing to ask for", Toast.LENGTH_SHORT).show();
            return;
        }
        // Wi-Fi and Bluetooth aren't apps: offered as pills after the app icons, not in the grid.
        List<String> controls = new ArrayList<>();
        for (String id : BuiltInApps.ALL) if (pkgs.remove(id)) controls.add(id);

        // Icons, not a text list: easier to find the app you mean.
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, pad, pad, pad);
        AppGrid grid = new AppGrid(this);
        body.addView(grid);
        // Side by side, centred; "settings" goes on its own line so both fit across the dialog.
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams pillsLp = new LinearLayout.LayoutParams(-1, -2);
        pillsLp.topMargin = dp(16);
        body.addView(pills, pillsLp);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
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
        for (AppGrid.Entry e : AppGrid.load(this, controls, false)) {
            // Icon and name in a rounded pill, like the ⋮ menu's.
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackground(rounded(Color.rgb(52, 60, 84), 24));
            pill.setPadding(dp(8), dp(8), dp(18), dp(8));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(e.icon);
            pill.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView name = new TextView(this);
            name.setText(e.label.toString().replace(" settings", "\nsettings"));
            name.setTextColor(Color.WHITE);
            name.setTextSize(15);
            name.setLineSpacing(0, 0.9f);
            name.setPadding(dp(10), 0, 0, 0);
            pill.addView(name);
            pill.setOnClickListener(v -> {
                pick.dismiss();
                askHowLong(e);
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.setMarginStart(pills.getChildCount() > 0 ? dp(10) : 0);
            pills.addView(pill, pl);
        }
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
            registerReceiver(batteryChanged, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
            receiversOn = true;
        }
        Policy.applySoon(this);
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
            unregisterReceiver(batteryChanged);
        }
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Shows the low-battery notice while the level is at or below the low mark and it isn't charging. */
    private void updateBattery() {
        int level = Battery.level(this);
        boolean low = level >= 0 && level <= Battery.lowAt(this) && !Battery.charging(this);
        batteryText.setText("Battery low (" + level + "%). Plug in to charge.");
        batteryText.setVisibility(low ? View.VISIBLE : View.GONE);
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
        Policy.storeParentUntil(this, 0);
        Policy.applySoon(this);
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
        // A clear PIN field: big centred dots, a "PIN" hint, the number keyboard up straight away,
        // and the keyboard's Done key unlocks.
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setHint("PIN");
        in.setGravity(Gravity.CENTER);
        in.setTextSize(28);
        in.setLetterSpacing(0.3f);
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Parent PIN")
                .setView(box)
                .setPositiveButton("Unlock", (d, w) -> unlockWith(in.getText().toString()))
                .setNegativeButton("Cancel", null)
                .create();
        in.setOnEditorActionListener((v, action, ev) -> {
            dialog.dismiss();
            unlockWith(in.getText().toString());
            return true;
        });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        // Put the keyboard away with the dialog; otherwise it can stay up (or leave a keyboard-sized gap)
        // over the home screen, which has nothing to type into.
        dialog.setOnDismissListener(d -> getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(in.getWindowToken(), 0));
        dialog.show();
        in.requestFocus();
    }

    /** Phone apps (dialers) always lead the home screen; everything else follows alphabetically. */
    private static final java.util.Set<String> DIALERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.dialer", "com.android.dialer"));

    private static List<AppGrid.Entry> phoneFirstThenByName(List<AppGrid.Entry> apps) {
        apps.sort((a, b) -> {
            boolean pa = DIALERS.contains(a.pkg), pb = DIALERS.contains(b.pkg);
            if (pa != pb) return pa ? -1 : 1;
            return a.label.toString().compareToIgnoreCase(b.label.toString());
        });
        return apps;
    }

    /** Checks the PIN and, if right, starts 15 minutes of parent mode; otherwise says why not. */
    private void unlockWith(String pin) {
        long r = Policy.checkPin(this, pin);
        if (r == 0) {
            Policy.storeParentUntil(this, System.currentTimeMillis() + 15 * 60_000L);
            Policy.applySoon(this);
            enforceLockTask();
        } else if (r > 0) {
            Toast.makeText(this, "Try again in " + r + " s", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
        }
    }

    private void launch(String pkg) {
        // Wi-Fi and Bluetooth are screens of this app: open them on top of the home screen. (The new-task
        // flags below, meant for other apps, would only bring this app's own task forward.)
        if (BuiltInApps.is(pkg)) {
            startActivity(BuiltInApps.launch(this, pkg));
            return;
        }
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

        List<String> scheduled = new ArrayList<>(Schedule.allowedNow(this));
        java.util.Map<String, Long> grants = Grants.load(this);

        // Wi-Fi and Bluetooth go behind the ⋮ button, not on the grids.
        quickAllowed.clear();
        for (String q : QUICK) {
            if (scheduled.contains(q) || grants.containsKey(q)) quickAllowed.add(q);
        }
        scheduled.removeAll(QUICK);

        homeGrid.setApps(phoneFirstThenByName(AppGrid.load(this, scheduled, true)), e -> launch(e.pkg));

        List<String> extra = new ArrayList<>();
        for (String p : grants.keySet()) if (!scheduled.contains(p) && !QUICK.contains(p)) extra.add(p);
        List<AppGrid.Entry> allowed = AppGrid.load(this, extra, true);
        for (AppGrid.Entry e : allowed) e.note = "until " + Remote.time(this, grants.get(e.pkg));
        allowedGrid.setApps(allowed, e -> launch(e.pkg));
        showIf(!allowed.isEmpty(), allowedHeading, allowedGrid);

        // Apps a parent paused that would otherwise be available now: greyed out, tap says until when.
        java.util.Map<String, Long> blocks = Grants.loadBlocks(this);
        List<String> pausedPkgs = new ArrayList<>();
        java.util.Map<String, Integer> masks = Schedule.masks(this);
        for (String p : blocks.keySet()) {
            if (QUICK.contains(p)) continue;
            Integer m = masks.get(p);
            if (m != null && (m & mode) != 0) pausedPkgs.add(p);
        }
        List<AppGrid.Entry> paused = phoneFirstThenByName(AppGrid.load(this, pausedPkgs, false));
        for (AppGrid.Entry e : paused) {
            e.note = "until " + Remote.time(this, blocks.get(e.pkg));
            e.dimmed = true;
        }
        pausedGrid.setApps(paused, e -> Toast.makeText(this,
                e.label + " is paused until " + Remote.time(this, blocks.get(e.pkg)), Toast.LENGTH_SHORT).show());
        showIf(!paused.isEmpty(), pausedHeading, pausedGrid);
    }

    /**
     * Opens the menu above the ⋮ button: a pill for Wi-Fi and Bluetooth when allowed right now, then Power,
     * like a floating action button's expanded actions.
     */
    private void showQuickMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.END);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu, -2, -2, true);
        List<AppGrid.Entry> items = new ArrayList<>(AppGrid.load(this, quickAllowed, false));
        items.add(new AppGrid.Entry(POWER, "Power", getDrawable(android.R.drawable.ic_lock_power_off)));
        for (AppGrid.Entry e : items) {
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackground(rounded(Color.rgb(52, 60, 84), 26));
            pill.setPadding(dp(10), dp(8), dp(20), dp(8));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(e.icon);
            pill.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView label = new TextView(this);
            label.setText(e.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(16);
            label.setPadding(dp(12), 0, 0, 0);
            pill.addView(label);
            pill.setOnClickListener(v -> {
                popup.dismiss();
                if (POWER.equals(e.pkg)) showPower(); else launch(e.pkg);
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.bottomMargin = dp(10);
            menu.addView(pill, pl);
        }
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setElevation(dp(8));
        // Just above the button, right edges lined up.
        popup.showAsDropDown(quickButton, quickButton.getWidth() - menu.getMeasuredWidth(),
                -quickButton.getHeight() - menu.getMeasuredHeight() - dp(6));
    }

    /** Not an app: the quick menu's Power item. */
    private static final String POWER = "power";

    /**
     * Android's power menu (Power off, Restart, Emergency). If screen view's service isn't running, falls
     * back to offering a restart, which the device owner can do itself (powering off needs the system menu).
     */
    private void showPower() {
        if (ScreenService.showPowerMenu()) return;
        new AlertDialog.Builder(this).setTitle("Restart this device?")
                .setMessage("To turn it off instead, hold the power button.")
                .setPositiveButton("Restart", (d, w) -> {
                    try { Policy.dpm(this).reboot(Policy.admin(this)); } catch (Exception e) {
                        Toast.makeText(this, "Couldn't restart", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void showIf(boolean show, View... views) {
        for (View v : views) v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** A small grey section title ("Allowed for now", "Paused for now") added to {@code parent}. */
    private TextView sectionHeading(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(170, 176, 190));
        t.setTextSize(14);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(36);
        hl.bottomMargin = dp(12);
        hl.leftMargin = dp(8);
        parent.addView(t, hl);
        return t;
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
