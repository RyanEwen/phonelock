package home.phonelock;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Uninstalls apps at a parent's request. Only apps installed on the device (not built-in system apps) and
 * never this app. As device owner the removal needs no confirmation on the device; the "no uninstalling"
 * restriction is lifted just for the request and restored by the next {@link Policy#apply}.
 */
final class AppRemover {
    private static final String ACTION_RESULT = "home.phonelock.UNINSTALL_RESULT";

    private AppRemover() {}

    /** Whether {@code pkg} can be uninstalled: installed for this user, not a system app, not us. */
    static boolean removable(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0 && (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Of {@code pkgs}, the ones that can be uninstalled. */
    static List<String> removableOf(Context c, Iterable<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) if (removable(c, p)) out.add(p);
        return out;
    }

    /** Starts uninstalling {@code pkg} for parent {@code who}; the result arrives in {@link Result}. */
    static void uninstall(Context c, String pkg, String who) {
        String label = Remote.label(c, pkg);
        if (!removable(c, pkg)) {
            Remote.event(c, label + " can't be uninstalled", who, false);
            return;
        }
        try {
            Policy.dpm(c).clearUserRestriction(Policy.admin(c), "no_uninstall_apps");
            Intent result = new Intent(ACTION_RESULT).setPackage(c.getPackageName())
                    .putExtra("label", label).putExtra("who", who).putExtra("pkg", pkg);
            PendingIntent pi = PendingIntent.getBroadcast(c, pkg.hashCode(), result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            c.getPackageManager().getPackageInstaller().uninstall(pkg, pi.getIntentSender());
        } catch (Exception e) {
            Log.w(Policy.TAG, "uninstall " + pkg + ": " + e);
            Policy.apply(c);
            Remote.event(c, "Couldn't uninstall " + label, who, false);
        }
    }

    /** Receives the uninstall outcome: restores the restriction and tells everyone. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String label = i.getStringExtra("label"), who = i.getStringExtra("who");
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Shouldn't happen for a device owner; never show a prompt the child could answer.
                Policy.apply(c);
                Remote.event(c, "Couldn't uninstall " + label + " without asking on the device", who, false);
                return;
            }
            Policy.apply(c);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                Remote.notifyKid(c, who + " uninstalled " + label);
                Remote.event(c, who + ": uninstalled " + label, who, false);
            } else {
                Remote.event(c, "Couldn't uninstall " + label, who, false);
            }
            Remote.sendState(c, true);
        }
    }
}
