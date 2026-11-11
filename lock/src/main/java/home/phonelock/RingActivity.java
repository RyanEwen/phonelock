package home.phonelock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen "a parent is trying to reach you" screen shown while {@link Ringer} rings. */
public class RingActivity extends Activity {
    static final String ACTION_CLOSE = "home.phonelock.RING_CLOSED";

    private TextView title;

    /** Closes this screen when the ringing stops elsewhere (parent, timer). */
    private final BroadcastReceiver closer = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Light the screen and show over the keyguard so the message is seen right away.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(120, 24, 32));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Tap Stop, then check in with them.");
        hint.setTextColor(Color.rgb(255, 210, 210));
        hint.setTextSize(18);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(16);
        root.addView(hint, hl);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setTextSize(24);
        stop.setOnClickListener(v -> {
            String name = Policy.prefs(this).getString("device_name", "The device");
            Ringer.stop(this, name + " stopped the ringing", null);
            finish();
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(220), dp(88));
        sl.topMargin = dp(48);
        root.addView(stop, sl);

        setContentView(root);
        registerReceiver(closer, new IntentFilter(ACTION_CLOSE), Context.RECEIVER_NOT_EXPORTED);
        bind();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Ringer.isRinging()) finish();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(closer);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { /* Stop is the way out, so the ring isn't dismissed by accident */ }

    private void bind() {
        String who = Ringer.caller();
        title.setText((who.isEmpty() ? "A parent" : who) + " is trying to reach you");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
