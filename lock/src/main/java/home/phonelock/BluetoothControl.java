package home.phonelock;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Bluetooth for the child's Wi-Fi &amp; Bluetooth screen: switch it on or off, list paired devices, find nearby
 * ones and pair with one (headphones, speakers). Switching Bluetooth on and off is refused to ordinary apps
 * since Android 13 but allowed for the device owner.
 *
 * <p>Limits: devices that show a confirmation code (phones, computers) need Android's pairing dialog, which
 * the lock keeps closed, so pairing is for accessories. Forgetting a device isn't available to apps; a parent
 * does that in Settings (parent mode). New pairings are reported to the parents.
 */
@SuppressWarnings("MissingPermission") // granted by the device owner (Policy.OWN_PERMISSIONS)
final class BluetoothControl {
    private BluetoothControl() {}

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    static boolean available(Context c) {
        return adapter(c) != null;
    }

    static boolean isOn(Context c) {
        BluetoothAdapter a = adapter(c);
        return a != null && a.isEnabled();
    }

    /** Switches Bluetooth on or off; false if Android refused. */
    @SuppressWarnings("deprecation")
    static boolean setOn(Context c, boolean on) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return false;
        try {
            return on ? a.enable() : a.disable();
        } catch (Exception e) {
            Log.w(Policy.TAG, "bluetooth on/off: " + e);
            return false;
        }
    }

    /** Paired devices, by name. */
    static List<BluetoothDevice> paired(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = adapter(c);
        if (a == null) return out;
        try {
            out.addAll(a.getBondedDevices());
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "bluetooth paired: " + e);
        }
        out.sort((x, y) -> name(x).compareToIgnoreCase(name(y)));
        return out;
    }

    /** Starts looking for nearby devices; each arrives as BluetoothDevice.ACTION_FOUND. */
    static void discover(Context c) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return;
        try {
            if (a.isDiscovering()) a.cancelDiscovery();
            a.startDiscovery();
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "bluetooth discover: " + e);
        }
    }

    static void stopDiscovery(Context c) {
        BluetoothAdapter a = adapter(c);
        try {
            if (a != null && a.isDiscovering()) a.cancelDiscovery();
        } catch (SecurityException ignored) {}
    }

    /** Starts pairing with {@code d}; the outcome arrives as BluetoothDevice.ACTION_BOND_STATE_CHANGED. */
    static boolean pair(Context c, BluetoothDevice d) {
        stopDiscovery(c);
        try {
            return d.createBond();
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "bluetooth pair: " + e);
            return false;
        }
    }

    /** Called when pairing finishes: tells the parents about a new device. */
    static void onPaired(Context c, BluetoothDevice d) {
        Outbox.alert(c, "Paired a new Bluetooth device: " + name(d));
    }

    /** A device's name, or its address when it has none. */
    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }
}
