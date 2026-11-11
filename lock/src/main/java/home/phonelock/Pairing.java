package home.phonelock;

import android.content.Context;

/**
 * Stores the parent pairing: the ntfy server and topic, the shared AES secret and this phone's display
 * name. Set over adb by {@link ConfigReceiver}, during setup from the parent app.
 */
final class Pairing {
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
}
