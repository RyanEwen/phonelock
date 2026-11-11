package home.phonelock;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * adb-only control. Examples:
 *   am broadcast -n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG --es cmd pin --es value 1234
 *   ... --es cmd apps    --es value "com.a,com.b"   (home screen apps, in order)
 *   ... --es cmd support --es value "com.x"         (allowed in lock, not shown)
 *   ... --es cmd hide    --es value "com.y"         (always hide)
 *   ... --es cmd dns     --es value abc123.dns.nextdns.io   (empty value = automatic)
 *   ... --es cmd debug   --es value on|off          (keep adb available while locked)
 *   ... --es cmd parent  --es value 30              (parent mode for N minutes)
 *   ... --es cmd lock
 *   ... --es cmd apply
 *   ... --es cmd status
 *   ... --es cmd release   (remove all policy and stop being device owner)
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        String value = intent.getStringExtra("value");
        if (cmd == null) cmd = "status";
        PendingResult pr = goAsync();
        final String fcmd = cmd;
        new Thread(() -> {
            String out;
            try {
                out = run(c, fcmd, value);
            } catch (Exception e) {
                out = "ERROR " + e;
            }
            Log.i(Policy.TAG, "cmd " + fcmd + ": " + out);
            pr.setResultCode(1);
            pr.setResultData(out);
            pr.finish();
        }).start();
    }

    private String run(Context c, String cmd, String value) throws Exception {
        DevicePolicyManager dpm = Policy.dpm(c);
        ComponentName a = Policy.admin(c);
        switch (cmd) {
            case "pin":
                if (value == null || value.length() < 4) return "PIN must be at least 4 characters";
                Policy.setPin(c, value);
                return "PIN set";
            case "apps":
                Policy.prefs(c).edit().putString("apps", value == null ? "" : value).apply();
                Policy.apply(c);
                return "apps=" + Policy.visibleApps(c);
            case "support":
                Policy.prefs(c).edit().putString("support", value == null ? "" : value).apply();
                Policy.apply(c);
                return "support=" + Policy.extraSupport(c);
            case "hide":
                Policy.prefs(c).edit().putString("hide", value == null ? "" : value).apply();
                Policy.apply(c);
                return "hide=" + value;
            case "dns":
                dpm.clearUserRestriction(a, "no_config_private_dns");
                int r;
                if (value == null || value.isEmpty()) {
                    r = dpm.setGlobalPrivateDnsModeOpportunistic(a);
                } else {
                    r = dpm.setGlobalPrivateDnsModeSpecifiedHost(a, value);
                }
                Policy.prefs(c).edit().putString("dns", value == null ? "" : value).apply();
                Policy.apply(c);
                return "dns result=" + r + " (0=ok) mode=" + dpm.getGlobalPrivateDnsMode(a)
                        + " host=" + dpm.getGlobalPrivateDnsHost(a);
            case "debug":
                Policy.prefs(c).edit().putBoolean("allow_debugging", "on".equals(value)).apply();
                Policy.apply(c);
                return "allow_debugging=" + "on".equals(value);
            case "parent": {
                int mins = value == null ? 30 : Integer.parseInt(value);
                Policy.setParentMode(c, System.currentTimeMillis() + mins * 60_000L);
                return "parent mode " + mins + " min";
            }
            case "lock":
                Policy.setParentMode(c, 0);
                return "locked";
            case "apply":
                Policy.apply(c);
                return "applied";
            case "remote": {
                // value = topic, or "server|topic"
                String v = value == null ? "" : value.trim();
                String server = "https://ntfy.sh", topic = v;
                int bar = v.indexOf('|');
                if (bar > 0) { server = v.substring(0, bar); topic = v.substring(bar + 1); }
                Pairing.setRemote(c, server, topic);
                return "remote=" + server + "/" + topic;
            }
            case "name":
                // Display name the parent apps show for this phone.
                Pairing.setName(c, value);
                return "name=" + value;
            case "secret":
                // 32-byte base64url pairing secret shared with the parent companion apps.
                Pairing.setSecret(c, value);
                return "secret " + (value == null || value.isEmpty() ? "cleared" : "set");
            case "say":
                // Run a remote-style command locally, e.g. --es value "allow whatsapp 5m"
                return Grants.command(c, value == null ? "" : value);
            case "release":
                Policy.releaseAll(c);
                dpm.clearDeviceOwnerApp(c.getPackageName());
                return "released; uninstall with: adb uninstall home.phonelock";
            case "status":
            default:
                return "owner=" + Policy.isOwner(c)
                        + " parent=" + Policy.inParentMode(c)
                        + " pin=" + Policy.hasPin(c)
                        + " apps=" + Policy.visibleApps(c)
                        + " hidden=" + Policy.prefs(c).getString("hidden_by_us", "")
                        + " dns=" + (Policy.isOwner(c) ? dpm.getGlobalPrivateDnsHost(a) : "?")
                        + " debug=" + Policy.prefs(c).getBoolean("allow_debugging", true);
        }
    }
}
