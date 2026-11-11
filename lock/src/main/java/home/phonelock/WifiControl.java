package home.phonelock;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wi-Fi management for the child's Wi-Fi &amp; Bluetooth screen, without opening Settings: switch Wi-Fi on or off,
 * list nearby networks, join one (with its password), and forget one. Android stopped ordinary apps doing
 * this in Android 10, but still allows it for the device owner, which this app is.
 *
 * <p>Joining a network that wasn't saved before is reported to the parents once it actually connects; a new
 * network whose password turns out wrong is removed again. Reports go through the {@link Outbox}, since
 * changing networks is exactly when the phone may be briefly offline.
 */
@SuppressWarnings("deprecation") // WifiConfiguration and friends: still the device owner's way to do this.
final class WifiControl {
    /** One nearby network: its name, whether it needs a password, signal (0 to 4), and whether it's saved. */
    static final class Network {
        final String ssid;
        final boolean secured;
        final int bars;
        final boolean saved;
        final boolean connected;

        Network(String ssid, boolean secured, int bars, boolean saved, boolean connected) {
            this.ssid = ssid;
            this.secured = secured;
            this.bars = bars;
            this.saved = saved;
            this.connected = connected;
        }
    }

    /** A network saved by {@link #join} that hasn't connected yet: reported on connect, removed on a bad password. */
    private static String pendingSsid;
    private static int pendingId = -1;

    private WifiControl() {}

    private static WifiManager wifi(Context c) {
        return c.getApplicationContext().getSystemService(WifiManager.class);
    }

    static boolean isOn(Context c) {
        return wifi(c).isWifiEnabled();
    }

    /** Switches Wi-Fi on or off; false if Android refused. */
    static boolean setOn(Context c, boolean on) {
        try {
            boolean ok = wifi(c).setWifiEnabled(on);
            if (ok && !on) Outbox.note(c, "Wi-Fi was switched off on the phone");
            return ok;
        } catch (Exception e) {
            Log.w(Policy.TAG, "wifi on/off: " + e);
            return false;
        }
    }

    /** Asks for a fresh scan; results arrive with SCAN_RESULTS_AVAILABLE_ACTION. */
    static void scan(Context c) {
        try {
            wifi(c).startScan();
        } catch (Exception e) {
            Log.w(Policy.TAG, "wifi scan: " + e);
        }
    }

    /** The current network's name, or null. */
    static String current(Context c) {
        WifiInfo info = wifi(c).getConnectionInfo();
        String s = info == null ? null : unquote(info.getSSID());
        return s == null || s.isEmpty() || s.equals("<unknown ssid>") ? null : s;
    }

    /** Nearby networks (strongest first, one per name), plus saved ones in range. */
    static List<Network> nearby(Context c) {
        WifiManager wm = wifi(c);
        String connected = current(c);
        List<String> saved = savedNames(c);
        Map<String, ScanResult> best = new LinkedHashMap<>();
        try {
            for (ScanResult r : wm.getScanResults()) {
                String name = r.SSID;
                if (name == null || name.isEmpty()) continue;
                ScanResult prev = best.get(name);
                if (prev == null || r.level > prev.level) best.put(name, r);
            }
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "wifi results: " + e);
        }
        List<Network> out = new ArrayList<>();
        for (ScanResult r : best.values()) {
            out.add(new Network(r.SSID, secured(r), WifiManager.calculateSignalLevel(r.level, 5),
                    saved.contains(r.SSID), r.SSID.equals(connected)));
        }
        out.sort((a, b) -> a.connected != b.connected ? (a.connected ? -1 : 1) : Integer.compare(b.bars, a.bars));
        return out;
    }

    private static boolean secured(ScanResult r) {
        String caps = r.capabilities == null ? "" : r.capabilities;
        return caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE") || caps.contains("PSK");
    }

    private static List<String> savedNames(Context c) {
        List<String> out = new ArrayList<>();
        try {
            for (WifiConfiguration w : wifi(c).getConfiguredNetworks()) out.add(unquote(w.SSID));
        } catch (Exception e) {
            Log.w(Policy.TAG, "wifi saved: " + e);
        }
        return out;
    }

    private static WifiConfiguration saved(Context c, String ssid) {
        try {
            for (WifiConfiguration w : wifi(c).getConfiguredNetworks()) {
                if (ssid.equals(unquote(w.SSID))) return w;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Joins {@code ssid}: a saved network directly, otherwise saved first with {@code password} (null or empty
     * for an open network). Returns false if Android refused.
     */
    static boolean join(Context c, String ssid, String password) {
        WifiManager wm = wifi(c);
        WifiConfiguration existing = saved(c, ssid);
        int id;
        boolean isNew = existing == null;
        if (isNew) {
            WifiConfiguration w = new WifiConfiguration();
            w.SSID = "\"" + ssid + "\"";
            if (password == null || password.isEmpty()) {
                w.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                // WPA3-only networks need SAE; mixed WPA2/WPA3 ones accept the WPA2 (PSK) config.
                if (saeOnly(c, ssid)) w.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                w.preSharedKey = "\"" + password + "\"";
            }
            id = wm.addNetwork(w);
        } else {
            id = existing.networkId;
        }
        if (id < 0) return false;
        boolean ok = wm.enableNetwork(id, true);
        synchronized (WifiControl.class) {
            pendingSsid = ok && isNew ? ssid : null;
            pendingId = ok && isNew ? id : -1;
        }
        if (!ok && isNew) wm.removeNetwork(id);
        return ok;
    }

    /** Whether nearby {@code ssid} offers only WPA3 (SAE), with no WPA2 fallback. */
    private static boolean saeOnly(Context c, String ssid) {
        try {
            for (ScanResult r : wifi(c).getScanResults()) {
                if (!ssid.equals(r.SSID)) continue;
                String caps = r.capabilities == null ? "" : r.capabilities;
                return caps.contains("SAE") && !caps.contains("PSK");
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Follows a network {@link #join} just saved: tells the parents once it's connected. Called on
     * NETWORK_STATE_CHANGED while the Wi-Fi screen is open.
     */
    static void onConnected(Context c) {
        String ssid;
        synchronized (WifiControl.class) {
            WifiInfo info = wifi(c).getConnectionInfo();
            if (pendingSsid == null || info == null || !pendingSsid.equals(unquote(info.getSSID()))) return;
            ssid = pendingSsid;
            pendingSsid = null;
            pendingId = -1;
        }
        Outbox.alert(c, "Joined a new Wi-Fi network: " + ssid);
    }

    /**
     * Follows a network {@link #join} just saved: a wrong password removes it again, so it isn't left saved
     * and retried. Called on SUPPLICANT_STATE_CHANGED with an error. Returns the network's name if removed.
     */
    static String onAuthFailed(Context c) {
        synchronized (WifiControl.class) {
            if (pendingSsid == null) return null;
            String ssid = pendingSsid;
            wifi(c).removeNetwork(pendingId);
            pendingSsid = null;
            pendingId = -1;
            return ssid;
        }
    }

    /** Forgets a saved network. */
    static boolean forget(Context c, String ssid) {
        WifiConfiguration w = saved(c, ssid);
        if (w == null) return false;
        boolean ok = wifi(c).removeNetwork(w.networkId);
        if (ok) Outbox.note(c, "Forgot the Wi-Fi network " + ssid);
        return ok;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
