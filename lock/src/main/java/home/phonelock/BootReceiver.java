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
        // Pairs with "Turning off": parents see when it came back, even if it starts without signal.
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            int level = Battery.level(c);
            Outbox.note(c, "Turned on" + (level >= 0 ? " (battery " + level + "%)" : ""));
        }
        Policy.apply(c);
        RemoteService.start(c);
        // Bring our home screen (and the kiosk) straight back: while this app was being updated, Android
        // fell back to the system launcher, which would otherwise stay in front until Home is pressed.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            Updater.reportIfUpdated(c);
            try {
                c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.w(Policy.TAG, "home after update: " + e);
            }
        }
    }
}
