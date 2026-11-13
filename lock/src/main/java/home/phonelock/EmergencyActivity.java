package home.phonelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The SOS button's screen. Call 911 comes first (parents can't help as fast); below it, whether the alert
 * reached her parents and who is on it, one-tap messages and a box to type her own, "Sent by mistake" for
 * the first minutes, and a way back home. The alert itself was already sent ({@link Emergency#start}).
 */
public class EmergencyActivity extends Activity {
    /** One-tap messages, quickest to send when typing is hard. */
    private static final String[] QUICK = {"I'm hurt", "I'm scared", "I'm lost", "Come get me", "Call me"};
    private static final long REFRESH_MS = 5_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView sent;
    private Button cancel;

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            bind();
        }
    };

    /** Re-checks every few seconds, so "Sent by mistake" goes away when its time is up. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            bind();
            h.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        getWindow().setStatusBarColor(Color.rgb(60, 12, 16));
        getWindow().setNavigationBarColor(Color.rgb(60, 12, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(60, 12, 16));
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = text("Emergency", 30, Color.WHITE);
        root.addView(title, wide(0));

        // 911 first and biggest.
        Button call = button("Call 911", Color.rgb(230, 50, 45), 22);
        call.setOnClickListener(v -> call911());
        root.addView(call, sized(-1, dp(72), dp(18)));

        status = text("", 17, Color.rgb(255, 220, 220));
        root.addView(status, wide(dp(18)));

        // Messages: one tap each, or her own words.
        root.addView(text("Tell them more", 15, Color.rgb(255, 190, 190)), wide(dp(22)));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < QUICK.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                quick.addView(row, wide(dp(8)));
            }
            String q = QUICK[i];
            Button qb = button(q, Color.rgb(110, 30, 36), 16);
            qb.setOnClickListener(v -> send(q));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            if (i % 2 == 1) lp.leftMargin = dp(8);
            row.addView(qb, lp);
        }
        root.addView(quick, wide(dp(4)));

        LinearLayout own = new LinearLayout(this);
        own.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this);
        input.setHint("Type a message");
        input.setHintTextColor(Color.rgb(200, 150, 150));
        input.setTextColor(Color.WHITE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        Runnable sendTyped = () -> {
            String t = input.getText().toString();
            if (t.trim().isEmpty()) return;
            send(t);
            input.setText("");
        };
        input.setOnEditorActionListener((v, action, e) -> {
            sendTyped.run();
            return true;
        });
        own.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button go = button("Send", Color.rgb(110, 30, 36), 16);
        go.setOnClickListener(v -> sendTyped.run());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, dp(52));
        gl.leftMargin = dp(8);
        own.addView(go, gl);
        root.addView(own, wide(dp(10)));

        sent = text("", 14, Color.rgb(255, 200, 200));
        root.addView(sent, wide(dp(10)));

        cancel = button("Sent by mistake", Color.rgb(80, 20, 26), 15);
        cancel.setOnClickListener(v -> confirmCancel());
        root.addView(cancel, sized(-1, dp(48), dp(22)));

        Button home = button("Back to home screen", Color.rgb(80, 20, 26), 15);
        home.setOnClickListener(v -> finish());
        root.addView(home, sized(-1, dp(48), dp(8)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(60, 12, 16));
        scroll.addView(root);
        setContentView(scroll);
        registerReceiver(changed, new IntentFilter(Emergency.ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(changed);
        super.onDestroy();
    }

    /** Shows where the alert stands: sending, sent, who is on it, or cancelled. */
    private void bind() {
        StringBuilder s = new StringBuilder();
        if (!Remote.paired(this)) {
            s.append("This phone isn't set up to reach your parents. Call 911 if you need help.");
        } else if (Emergency.cancelled(this)) {
            s.append("Alert cancelled. Your parents were told it was sent by mistake.");
        } else if (Emergency.activeId(this).isEmpty()) {
            s.append("Your parents were alerted.");
        } else if (Emergency.allSent(this)) {
            s.append("Your parents have been alerted and can see where you are.");
        } else {
            s.append("Sending to your parents… If there's no signal it keeps trying.");
        }
        for (String who : Emergency.acks(this)) s.append("\n").append(who).append(" is on it.");
        status.setText(s);

        java.util.List<String> msgs = Emergency.messages(this);
        sent.setText(msgs.isEmpty() ? "" : "Sent: " + String.join(" · ", msgs));
        sent.setVisibility(msgs.isEmpty() ? View.GONE : View.VISIBLE);
        cancel.setVisibility(Emergency.canCancel(this) ? View.VISIBLE : View.GONE);
    }

    private void send(String text) {
        if (Emergency.activeId(this).isEmpty()) {
            Toast.makeText(this, "No alert in progress: hold SOS again", Toast.LENGTH_SHORT).show();
            return;
        }
        Emergency.message(this, text);
        bind();
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cancel the alert?")
                .setMessage("Your parents will be told it was sent by mistake.")
                .setPositiveButton("Yes, cancel it", (d, w) -> {
                    Emergency.cancel(this);
                    bind();
                })
                .setNegativeButton("No", null)
                .show();
    }

    /** Opens Android's emergency dialer (works inside the lock), or the plain dialer with 911 filled in. */
    private void call911() {
        try {
            // The emergency dialer (com.android.phone, the one the lock screen opens): allowed in the lock.
            startActivity(new Intent("com.android.phone.EmergencyDialer.DIAL", Uri.parse("tel:911"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(this, "Couldn't open the dialer", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- views ----------

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button button(String t, int color, int sp) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(sp);
        b.setTextColor(Color.WHITE);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams wide(int top) {
        return sized(-1, -2, top);
    }

    private static LinearLayout.LayoutParams sized(int w, int hgt, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, hgt);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
