package home.phonelock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** All device-owner policy lives here. Everything is idempotent and safe to re-run. */
public final class Policy {
    static final String TAG = "PhoneLock";
    /** Sent to our own package after every {@link #apply}. */
    static final String ACTION_CHANGED = "home.phonelock.POLICY_CHANGED";

    /**
     * Home-screen apps until a parent changes them: calls, contacts, calendar, camera, clock and calculator.
     * Several package names cover stock Android, Pixel and LineageOS; only installed ones show.
     */
    static final String[] DEFAULT_APPS = {
            "com.google.android.dialer",
            "com.android.dialer",
            "com.google.android.contacts",
            "com.android.contacts",
            "com.google.android.calendar",
            "com.google.android.GoogleCamera",
            "org.lineageos.aperture",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.google.android.calculator",
            "com.android.calculator2",
    };

    /** Allowed to run inside the lock, but not shown on the home screen. */
    static final String[] SUPPORT = {
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.documentsui",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "org.lineageos.glimpse",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
    };

    /** Never hidden even though they have a launcher icon (lock task still keeps them shut). */
    static final String[] NEVER_HIDE = {
            "com.android.settings",
            "com.android.vending",
            // Emergency / government alerts must keep working.
            "com.android.cellbroadcastreceiver",
            "com.android.cellbroadcastreceiver.module",
            "com.google.android.cellbroadcastreceiver",
            "com.android.stk",
    };

    /** Always applied while locked. */
    static final String[] RESTRICTIONS_LOCKED = {
            "no_sms",
            "no_modify_accounts",
            "no_factory_reset",
            "no_safe_boot",
            "no_add_user",
            "no_remove_user",
            "no_user_switch",
            "no_add_managed_profile",
            "no_add_clone_profile",
            "no_add_private_profile",
            "no_install_unknown_sources",
            "no_install_unknown_sources_globally",
            "no_apps_control",
            "no_uninstall_apps",
            "no_config_vpn",
            "no_config_private_dns",
            "no_config_date_time",
            "no_config_credentials",
            "no_config_tethering",
            "no_network_reset",
            "no_debugging_features",
            "no_usb_file_transfer",
            "no_mount_physical_media",
            "no_set_user_icon",
            "no_set_wallpaper",
            "no_config_location",
    };

    /** Runtime permissions the device owner grants to this app. */
    private static final String[] OWN_PERMISSIONS = {
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    };

    /** Lifted while a parent has unlocked with the PIN. */
    static final String[] RESTRICTIONS_LIFTED_FOR_PARENT = {
            "no_modify_accounts",
            "no_apps_control",
            "no_uninstall_apps",
            "no_config_private_dns",
            "no_config_date_time",
            "no_debugging_features",
            "no_usb_file_transfer",
            "no_config_vpn",
    };

    private Policy() {}

    static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static DevicePolicyManager dpm(Context c) {
        return c.getSystemService(DevicePolicyManager.class);
    }

    static boolean isOwner(Context c) {
        return dpm(c).isDeviceOwnerApp(c.getPackageName());
    }

    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences("phonelock", Context.MODE_PRIVATE);
    }

    static List<String> visibleApps(Context c) {
        String s = prefs(c).getString("apps", null);
        if (s == null) return Arrays.asList(DEFAULT_APPS);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** Apps usable right now: scheduled apps allowed in the current situation, plus temporary grants. */
    static List<String> homeApps(Context c) {
        List<String> out = new ArrayList<>(Schedule.allowedNow(c));
        for (String p : Grants.load(c).keySet()) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** Installed for this user, hidden or not. */
    static boolean isPresent(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            // Hidden apps are still "installed"; apps removed from this user (data kept) are not.
            return (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Apps a parent took off the "Give access" list; the child can't ask for them either. */
    static Set<String> notOffered(Context c) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : prefs(c).getString("not_offered", "").split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** Adds {@code pkg} to, or removes it from, {@link #notOffered}. */
    static void setOffered(Context c, String pkg, boolean offered) {
        Set<String> s = notOffered(c);
        if (offered) s.remove(pkg); else s.add(pkg);
        prefs(c).edit().putString("not_offered", String.join(",", s)).apply();
    }

    static Set<String> extraSupport(Context c) {
        String s = prefs(c).getString("support", "");
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    static boolean inParentMode(Context c) {
        return System.currentTimeMillis() < prefs(c).getLong("parent_until", 0);
    }

    /** Re-apply everything. Call often. */
    static synchronized void apply(Context c) {
        if (!isOwner(c)) {
            Log.w(TAG, "not device owner; nothing applied");
            return;
        }
        DevicePolicyManager dpm = dpm(c);
        ComponentName a = admin(c);
        boolean parent = inParentMode(c);

        // 1. Our activity is the only home screen.
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        dpm.clearPackagePersistentPreferredActivities(a, c.getPackageName());
        dpm.addPersistentPreferredActivity(a, home, new ComponentName(c, HomeActivity.class));

        // 2. Which packages may run inside the lock.
        Set<String> lockPkgs = new LinkedHashSet<>();
        lockPkgs.add(c.getPackageName());
        // Only the scheduled apps allowed in the current situation (normal, limited hours, bedtime).
        lockPkgs.addAll(Schedule.allowedNow(c));
        lockPkgs.addAll(Arrays.asList(SUPPORT));
        lockPkgs.addAll(extraSupport(c));
        java.util.Map<String, Long> grants = Grants.load(c);
        lockPkgs.addAll(grants.keySet());
        if (parent) {
            lockPkgs.add("com.android.settings");
            lockPkgs.add("com.android.vending");
        }
        dpm.setLockTaskPackages(a, lockPkgs.toArray(new String[0]));
        dpm.setLockTaskFeatures(a,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                        | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                        | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                        | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                        | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD);

        // 3. User restrictions.
        Set<String> lifted = parent
                ? new LinkedHashSet<>(Arrays.asList(RESTRICTIONS_LIFTED_FOR_PARENT))
                : new LinkedHashSet<>();
        // Debugging stays available until setup is finished and it is switched off explicitly.
        if (prefs(c).getBoolean("allow_debugging", true)) lifted.add("no_debugging_features");
        if (grants.containsKey(Grants.SMS_APP)) lifted.add("no_sms");
        for (String r : RESTRICTIONS_LOCKED) {
            try {
                if (lifted.contains(r)) dpm.clearUserRestriction(a, r);
                else dpm.addUserRestriction(a, r);
            } catch (Exception e) {
                Log.w(TAG, "restriction " + r + ": " + e);
            }
        }

        // Our own runtime permissions: notifications (messages to the kid) and location ("find").
        grantOwnPermissions(c, dpm, a);
        // "Find" needs location on; no_config_location (above) keeps it on.
        try { dpm.setLocationEnabled(a, true); } catch (Exception e) { Log.w(TAG, "location on: " + e); }
        // Only our screenshot service may be an accessibility service, so nothing else can read the screen.
        try {
            dpm.setPermittedAccessibilityServices(a, Collections.singletonList(c.getPackageName()));
        } catch (Exception e) {
            Log.w(TAG, "accessibility allowlist: " + e);
        }

        // 4. Hide every launchable app that isn't allowed.
        // Scheduled apps stay installed-visible outside their hours: the lock task allowlist already stops
        // them opening, and hiding would kill them nightly (dropping reminders, alarms and backups).
        Set<String> keep = new LinkedHashSet<>(lockPkgs);
        keep.addAll(Schedule.masks(c).keySet());
        hideApps(c, dpm, a, keep);

        Log.i(TAG, "applied; parent=" + parent + " lockPkgs=" + lockPkgs);
        // Let an open home screen redraw right away (new grant, schedule change) instead of on its next tick.
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
    }

    private static void grantOwnPermissions(Context c, DevicePolicyManager dpm, ComponentName a) {
        for (String perm : OWN_PERMISSIONS) {
            try {
                // Android shows "Your organization allows Home to access your location" on every grant,
                // and apply() runs often, so only grant what isn't already granted.
                if (dpm.getPermissionGrantState(a, c.getPackageName(), perm)
                        == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) continue;
                dpm.setPermissionGrantState(a, c.getPackageName(), perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Exception e) {
                Log.w(TAG, "grant " + perm + ": " + e);
            }
        }
    }

    private static void hideApps(Context c, DevicePolicyManager dpm, ComponentName a,
                                 Set<String> allowed) {
        PackageManager pm = c.getPackageManager();
        Set<String> keep = new LinkedHashSet<>(allowed);
        keep.addAll(Arrays.asList(NEVER_HIDE));
        Set<String> extraHide = new LinkedHashSet<>();
        for (String p : prefs(c).getString("hide", "").split(","))
            if (!p.trim().isEmpty()) extraHide.add(p.trim());

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> launchable = new LinkedHashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher,
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
            launchable.add(ri.activityInfo.packageName);
        }
        // Anything that can open web links is hidden too, launcher icon or not.
        Intent web = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"));
        web.addCategory(Intent.CATEGORY_BROWSABLE);
        for (ResolveInfo ri : pm.queryIntentActivities(web, PackageManager.MATCH_ALL)) {
            String p = ri.activityInfo.packageName;
            if (isBrowser(pm, p)) launchable.add(p);
        }
        launchable.addAll(extraHide);

        Set<String> wasHidden = new LinkedHashSet<>();
        for (String p : prefs(c).getString("hidden_by_us", "").split(","))
            if (!p.isEmpty()) wasHidden.add(p);

        Set<String> nowHidden = new LinkedHashSet<>();
        for (String p : launchable) {
            if (keep.contains(p) && !extraHide.contains(p)) continue;
            if (p.equals(c.getPackageName())) continue;
            try {
                if (dpm.setApplicationHidden(a, p, true) || dpm.isApplicationHidden(a, p)) {
                    nowHidden.add(p);
                }
            } catch (Exception e) {
                Log.w(TAG, "hide " + p + ": " + e);
            }
        }
        // Un-hide anything we hid earlier that is now allowed.
        for (String p : wasHidden) {
            if (!nowHidden.contains(p) && keep.contains(p) && !extraHide.contains(p)) {
                try { dpm.setApplicationHidden(a, p, false); } catch (Exception ignored) {}
            } else if (!nowHidden.contains(p)) {
                // still hidden from a previous run (hidden apps don't resolve), keep tracking it
                try { if (dpm.isApplicationHidden(a, p)) nowHidden.add(p); } catch (Exception ignored) {}
            }
        }
        prefs(c).edit().putString("hidden_by_us", String.join(",", nowHidden)).apply();
    }

    /** A real browser resolves a generic https link without a specific host filter. */
    private static boolean isBrowser(PackageManager pm, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://zz-not-a-real-site.invalid/"));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.setPackage(pkg);
        return !pm.queryIntentActivities(i, 0).isEmpty();
    }

    /** Remove every restriction and unhide everything we hid. Used before releasing. */
    static void releaseAll(Context c) {
        DevicePolicyManager dpm = dpm(c);
        ComponentName a = admin(c);
        for (String r : RESTRICTIONS_LOCKED) {
            try { dpm.clearUserRestriction(a, r); } catch (Exception ignored) {}
        }
        for (String p : prefs(c).getString("hidden_by_us", "").split(",")) {
            if (p.isEmpty()) continue;
            try { dpm.setApplicationHidden(a, p, false); } catch (Exception ignored) {}
        }
        try { dpm.setGlobalPrivateDnsModeOpportunistic(a); } catch (Exception ignored) {}
        try { dpm.setPermittedAccessibilityServices(a, null); } catch (Exception ignored) {}
        dpm.setLockTaskPackages(a, new String[0]);
        dpm.clearPackagePersistentPreferredActivities(a, c.getPackageName());
        prefs(c).edit().putString("hidden_by_us", "").apply();
    }

    // ---- PIN ----

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = toHex(salt);
        prefs(c).edit().putString("pin_salt", s).putString("pin_hash", hash(s, pin))
                .putInt("pin_fails", 0).putLong("pin_lock_until", 0).apply();
    }

    static boolean hasPin(Context c) {
        return prefs(c).getString("pin_hash", null) != null;
    }

    /** Returns 0 ok, >0 = seconds until another attempt is allowed, -1 wrong. */
    static long checkPin(Context c, String pin) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long until = p.getLong("pin_lock_until", 0);
        if (now < until) return (until - now) / 1000 + 1;
        String salt = p.getString("pin_salt", null), h = p.getString("pin_hash", null);
        if (salt != null && h != null && MessageDigest.isEqual(
                h.getBytes(StandardCharsets.US_ASCII), hash(salt, pin).getBytes(StandardCharsets.US_ASCII))) {
            p.edit().putInt("pin_fails", 0).apply();
            return 0;
        }
        int fails = p.getInt("pin_fails", 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt("pin_fails", fails);
        if (fails >= 3) {
            // 3 wrong: 1 min, then doubles each time, capped at a day.
            long secs = Math.min(86400L, 60L << Math.min(fails - 3, 10));
            e.putLong("pin_lock_until", now + secs * 1000);
        }
        e.apply();
        return -1;
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = (salt + ":" + pin).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 50000; i++) d = md.digest(d);
            return toHex(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
