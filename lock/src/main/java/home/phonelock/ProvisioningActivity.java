package home.phonelock;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * The two screens Android's setup wizard asks a device-owner app for when a phone is set up from a QR code
 * (tap the welcome screen 6 times, then scan the parent app's "Set up a child's phone" code):
 * <ul>
 *   <li>{@code GET_PROVISIONING_MODE}: we always answer "fully managed device" (this app is device owner).</li>
 *   <li>{@code ADMIN_POLICY_COMPLIANCE}: runs once we are device owner; stores the pairing and PIN from the
 *       QR code, switches off USB debugging (a family setting up this way has no PC), and applies the lock.</li>
 * </ul>
 * Neither shows any UI.
 */
public class ProvisioningActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String action = getIntent().getAction();
        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(action)) {
            setResult(RESULT_OK, new Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE));
        } else if (DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE.equals(action)) {
            completeSetup(getIntent());
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    /** Applies the QR code's pairing and the lock policy. Failures leave an unpaired but locked phone. */
    private void completeSetup(Intent i) {
        PersistableBundle extras = i.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle.class);
        boolean paired = Pairing.applyProvisioningExtras(this, extras);
        Log.i(Policy.TAG, "provisioned; paired=" + paired);
        Policy.prefs(this).edit().putBoolean("allow_debugging", false).apply();
        Policy.apply(this);
        RemoteService.start(this);
    }
}
