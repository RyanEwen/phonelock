package home.phonelock;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * Screens incoming calls (Android's call screening role, granted by tools/configure-lock.sh). When parents
 * limit incoming calls, calls from numbers off the safe list, and from hidden numbers, are rejected; they
 * still appear in the call log so parents can see them.
 */
public class IncomingCallGuard extends CallScreeningService {
    @Override
    public void onScreenCall(Call.Details details) {
        boolean incoming = details.getCallDirection() == Call.Details.DIRECTION_INCOMING;
        Uri handle = details.getHandle();
        String number = handle == null ? null : handle.getSchemeSpecificPart();
        boolean block = incoming && CallGuard.limitIncoming(this) && CallGuard.match(this, number) == null;

        CallResponse.Builder r = new CallResponse.Builder();
        if (block) r.setDisallowCall(true).setRejectCall(true).setSkipNotification(false).setSkipCallLog(false);
        respondToCall(details, r.build());

        if (block) Remote.event(this, "Blocked a call from " + CallGuard.describe(this, number), null, true);
    }
}
