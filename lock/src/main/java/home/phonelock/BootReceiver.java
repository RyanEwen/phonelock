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
    }
}
