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

    /**
     * Never hidden from the system, but listed for parents like any other app, starting on the hidden list
     * ({@link #notOffered}): a parent can show one and allow it for a while.
     */
    static final String[] HIDDEN_FROM_LIST_BY_DEFAULT = {
            "com.android.settings",
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
            // Contacts for the parents' safe list and contact management; accounts to add to Google.
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.GET_ACCOUNTS,
            // The call log for parents; Android may refuse this one to a device owner (restricted).
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_PHONE_STATE,
            // The Wi-Fi & Bluetooth screen: find and pair accessories.
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
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
        if (BuiltInApps.is(pkg)) return true;
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
        Set<String> out = new LinkedHashSet<>(csv(c, "not_offered"));
        // Settings starts on the hidden list: a parent can show it to allow it for a while in an edge case
        // (e.g. turning on USB debugging), without it ever being on offer to the child by default.
        Set<String> shown = csv(c, "offered_defaults");
        for (String p : HIDDEN_FROM_LIST_BY_DEFAULT) if (!shown.contains(p)) out.add(p);
        return out;
    }

    /** Adds {@code pkg} to, or removes it from, {@link #notOffered}. */
    static void setOffered(Context c, String pkg, boolean offered) {
        if (Arrays.asList(HIDDEN_FROM_LIST_BY_DEFAULT).contains(pkg)) {
            // Hidden by default, so what's remembered is that a parent chose to show it.
            Set<String> shown = csv(c, "offered_defaults");
            if (offered) shown.add(pkg); else shown.remove(pkg);
            prefs(c).edit().putString("offered_defaults", String.join(",", shown)).apply();
            return;
        }
        Set<String> s = csv(c, "not_offered");
        if (offered) s.remove(pkg); else s.add(pkg);
        prefs(c).edit().putString("not_offered", String.join(",", s)).apply();
    }

    /** A comma-separated set of packages stored under {@code key}. */
    private static Set<String> csv(Context c, String key) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : prefs(c).getString(key, "").split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    static Set<String> extraSupport(Context c) {
        String s = prefs(c).getString("support", "");
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /**
     * Records when parent mode ends (0: it's off), and tells {@link AppTime} so a parent's time on the device
     * isn't counted against the child's app limits. Every change to parent mode goes through here.
     */
    static void storeParentUntil(Context c, long untilMs) {
        prefs(c).edit().putLong("parent_until", untilMs).commit();
        AppTime.noteParentMode(c, untilMs);
    }

    static boolean inParentMode(Context c) {
        return System.currentTimeMillis() < prefs(c).getLong("parent_until", 0);
    }

    /**
     * Turns parent mode on until {@code untilMs} (0 turns it off), re-applies the policy, and brings the
     * home screen forward so it leaves or enters lock task and shows or hides the parent bar.
     */
    static void setParentMode(Context c, long untilMs) {
        storeParentUntil(c, untilMs);
        // In the background, like the home screen's own updates: a full apply can take seconds.
        applySoon(c);
        c.startActivity(new Intent(c, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("parent_changed", true));
    }

    /** Runs [applySoon]'s updates one after another, off the main thread. */
    private static final java.util.concurrent.ExecutorService APPLIER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean APPLY_QUEUED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long appliedAt;

    /**
     * Re-applies everything in the background, merging a burst of requests into one run. For the home
     * screen: a full apply hides, shows and suspends many apps (slow), and every app it shows makes Android
     * announce a package change, which used to trigger another apply on the main thread for each one and
     * freeze the screen (e.g. when parent mode starts).
     */
    static void applySoon(Context c) {
        Context app = c.getApplicationContext();
        if (!APPLY_QUEUED.compareAndSet(false, true)) return;
        APPLIER.execute(() -> {
            APPLY_QUEUED.set(false);
            try {
                apply(app);
            } catch (Exception e) {
                Log.w(TAG, "apply: " + e);
            }
        });
    }

    /** True for a few seconds after an apply: package changes then are most likely our own doing. */
    static boolean justApplied() {
        return System.currentTimeMillis() - appliedAt < 3_000;
    }

    /** Re-apply everything. Call often (from the main thread, prefer [applySoon]). */
    static synchronized void apply(Context c) {
        if (!isOwner(c)) {
            Log.w(TAG, "not device owner; nothing applied");
            return;
        }
        DevicePolicyManager dpm = dpm(c);
        ComponentName a = admin(c);
        boolean parent = inParentMode(c);

        // 1. Our activity is the only home screen. Only (re)registered when it isn't already: Android restarts
        // the home screen when this changes, and doing it on every apply made the home screen flash grey and
        // black in a loop during bursts of applies (parent mode starting).
        ComponentName ours = new ComponentName(c, HomeActivity.class);
        ResolveInfo current = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        boolean isHome = current != null && current.activityInfo != null
                && ours.equals(new ComponentName(current.activityInfo.packageName, current.activityInfo.name));
        if (!isHome) {
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.clearPackagePersistentPreferredActivities(a, c.getPackageName());
            dpm.addPersistentPreferredActivity(a, home, ours);
        }

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
        // Wi-Fi and Bluetooth are screens of this app, not packages (see BuiltInApps).
        dpm.setLockTaskPackages(a, lockPkgs.stream().filter(p -> !BuiltInApps.is(p)).toArray(String[]::new));
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
        // Scheduled apps aren't hidden outside their hours (hiding would wipe them from the launcher state
        // every night); instead they're suspended: closed, can't open, no notifications or sounds.
        Set<String> keep = new LinkedHashSet<>(lockPkgs);
        keep.addAll(Schedule.masks(c).keySet());
        hideApps(c, dpm, a, keep);

        // 5. Suspend scheduled apps that aren't usable right now (out of hours or paused), unless allowed
        //    for a while or a parent has unlocked the device.
        Set<String> suspend = new LinkedHashSet<>();
        if (!parent) {
            for (String p : Schedule.masks(c).keySet()) {
                if (!lockPkgs.contains(p) && !NEVER_SUSPEND.contains(p) && !BuiltInApps.is(p)) suspend.add(p);
            }
        }
        suspendApps(c, dpm, a, suspend);

        Log.i(TAG, "applied; parent=" + parent + " lockPkgs=" + lockPkgs);
        // Parent mode may just have ended: screen view must be on again if it was switched off meanwhile.
        ScreenViewGuard.check(c);
        appliedAt = System.currentTimeMillis();
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

    /**
     * Never suspended, even when unticked for a situation: a suspended dialer can't show incoming calls, and
     * a suspended clock wouldn't ring a morning alarm during bedtime.
     */
    private static final Set<String> NEVER_SUSPEND = new java.util.HashSet<>(Arrays.asList(
            "com.google.android.dialer", "com.android.dialer",
            "com.google.android.deskclock", "com.android.deskclock"));

    /** Suspends exactly {@code want} among the packages this app suspends, releasing the rest. */
    private static void suspendApps(Context c, DevicePolicyManager dpm, ComponentName a, Set<String> want) {
        Set<String> was = new LinkedHashSet<>();
        for (String p : prefs(c).getString("suspended_by_us", "").split(",")) if (!p.isEmpty()) was.add(p);
        Set<String> release = new LinkedHashSet<>(was);
        release.removeAll(want);
        try {
            if (!release.isEmpty()) dpm.setPackagesSuspended(a, release.toArray(new String[0]), false);
            Set<String> now = new LinkedHashSet<>(want);
            if (!want.isEmpty()) {
                String[] failed = dpm.setPackagesSuspended(a, want.toArray(new String[0]), true);
                now.removeAll(Arrays.asList(failed));
            }
            prefs(c).edit().putString("suspended_by_us", String.join(",", now)).apply();
        } catch (Exception e) {
            Log.w(TAG, "suspend: " + e);
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
        suspendApps(c, dpm, a, new LinkedHashSet<>());
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
