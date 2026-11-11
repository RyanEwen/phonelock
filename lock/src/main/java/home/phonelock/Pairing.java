package home.phonelock;

import android.content.Context;
import android.os.PersistableBundle;

/**
 * Stores the parent pairing: the ntfy server and topic, the shared AES secret and this phone's display
 * name. Set over adb by {@link ConfigReceiver}, or from the setup QR code by {@link ProvisioningActivity}.
 */
final class Pairing {
    /** Keys in the provisioning admin-extras bundle, written by the parent app's setup QR code. */
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_TOPIC = "topic";
    static final String EXTRA_SECRET = "secret";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_PIN = "pin";

    private Pairing() {}

    /** Sets the ntfy server and topic, forgetting the last-seen message, and reconnects. */
    static void setRemote(Context c, String server, String topic) {
        storeRemote(c, server, topic);
        RemoteService.restart(c);
    }

    /** Sets the 32-byte base64url secret (empty clears it) and reconnects. Throws if it isn't a valid key. */
    static void setSecret(Context c, String secret) {
        storeSecret(c, secret);
        RemoteService.restart(c);
    }

    private static void storeRemote(Context c, String server, String topic) {
        Policy.prefs(c).edit()
                .putString("ntfy_server", server)
                .putString("ntfy_topic", topic)
                .remove("ntfy_last_id")
                .apply();
    }

    private static void storeSecret(Context c, String secret) {
        if (secret != null && !secret.isEmpty()) Crypto.key(secret); // validates
        Policy.prefs(c).edit().putString("secret", secret == null ? "" : secret).apply();
    }

    /** Sets the name the parent apps show for this phone, and tells them. */
    static void setName(Context c, String name) {
        Policy.prefs(c).edit().putString("device_name", name == null || name.isEmpty() ? "Device" : name).apply();
        Remote.sendState(c, false);
    }

    /**
     * Applies the pairing (and parent PIN) carried in the setup QR code's admin extras. Returns false if the
     * bundle has no usable pairing; the phone is then locked but unpaired, and can be paired over adb.
     */
    static boolean applyProvisioningExtras(Context c, PersistableBundle extras) {
        if (extras == null) return false;
        String topic = extras.getString(EXTRA_TOPIC, "");
        String secret = extras.getString(EXTRA_SECRET, "");
        if (topic.isEmpty() || secret.isEmpty()) return false;
        // Store everything first and let the caller start the service once: back-to-back restarts can crash
        // a foreground service that hasn't called startForeground yet.
        try {
            storeSecret(c, secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        storeRemote(c, extras.getString(EXTRA_SERVER, "https://ntfy.sh"), topic);
        Policy.prefs(c).edit().putString("device_name", extras.getString(EXTRA_NAME, "Device")).apply();
        String pin = extras.getString(EXTRA_PIN, "");
        if (pin.length() >= 4) Policy.setPin(c, pin);
        return true;
    }
}
