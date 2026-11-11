package home.phonelock;

import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;

/**
 * Sees every outgoing call before it is placed (Android's call redirection role, granted by
 * tools/configure-lock.sh) and cancels calls to numbers off the safe list when parents limit outgoing
 * calls. Android never sends emergency numbers here, so 911 and the like always connect.
 */
public class OutgoingCallGuard extends CallRedirectionService {
    @Override
    public void onPlaceCall(Uri handle, PhoneAccountHandle account, boolean allowInteractiveResponse) {
        String number = handle == null ? null : handle.getSchemeSpecificPart();
        if (!CallGuard.limitOutgoing(this) || CallGuard.match(this, number) != null) {
            placeCallUnmodified();
            return;
        }
        cancelCall();
        String who = CallGuard.describe(this, number);
        Remote.notifyKid(this, "Calling " + who + " isn't allowed. Ask a parent to add it to your safe list.");
        Remote.event(this, "Tried to call " + who + " (not on the safe list)");
    }
}
