package home.phonelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        // A reboot always ends parent mode.
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            Policy.prefs(c).edit().putLong("parent_until", 0).apply();
        }
        Policy.apply(c);
        RemoteService.start(c);
        // Bring our home screen (and the kiosk) straight back: while this app was being updated, Android
        // fell back to the system launcher, which would otherwise stay in front until Home is pressed.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            try {
                c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.w(Policy.TAG, "home after update: " + e);
            }
        }
    }
}
