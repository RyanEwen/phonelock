package home.phonelock;

import android.content.Context;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who the child may call and be called by. Parents keep a safe list of numbers and can limit outgoing
 * and/or incoming calls to it. Emergency numbers never reach the call redirection service, so they always
 * go through regardless of this list.
 */
final class CallGuard {
    /** One safe number with the name shown to parents and in notices. */
    static final class Entry {
        final String name, number;

        Entry(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    private CallGuard() {}

    static boolean limitOutgoing(Context c) {
        return Policy.prefs(c).getBoolean("calls_limit_out", false);
    }

    static boolean limitIncoming(Context c) {
        return Policy.prefs(c).getBoolean("calls_limit_in", false);
    }

    static void setLimits(Context c, boolean outgoing, boolean incoming) {
        Policy.prefs(c).edit().putBoolean("calls_limit_out", outgoing).putBoolean("calls_limit_in", incoming).apply();
    }

    static List<Entry> safeList(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Policy.prefs(c).getString("calls_safe", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String n = o.optString("number").trim();
                if (!n.isEmpty()) out.add(new Entry(o.optString("name"), n));
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "safe list: " + e);
        }
        return out;
    }

    /** Replaces the safe list with the entries in {@code a} ({name, number}); blank numbers are dropped. */
    static void setSafeList(Context c, JSONArray a) throws Exception {
        JSONArray clean = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String n = o.optString("number").trim();
            if (n.isEmpty()) continue;
            clean.put(new JSONObject().put("name", o.optString("name").trim()).put("number", n));
        }
        Policy.prefs(c).edit().putString("calls_safe", clean.toString()).apply();
    }

    static JSONObject settingsJson(Context c) throws Exception {
        JSONArray safe = new JSONArray();
        for (Entry e : safeList(c)) safe.put(new JSONObject().put("name", e.name).put("number", e.number));
        return new JSONObject().put("limitOut", limitOutgoing(c)).put("limitIn", limitIncoming(c)).put("safe", safe);
    }

    /** The safe-list entry matching {@code number} (same number in any common format), or null. */
    static Entry match(Context c, String number) {
        if (number == null || number.isEmpty()) return null;
        String iso = countryIso(c);
        for (Entry e : safeList(c)) {
            if (PhoneNumberUtils.areSamePhoneNumber(number, e.number, iso)) return e;
        }
        return null;
    }

    private static String countryIso(Context c) {
        TelephonyManager tm = c.getSystemService(TelephonyManager.class);
        String iso = tm == null ? null : tm.getNetworkCountryIso();
        if (iso == null || iso.isEmpty()) iso = tm == null ? null : tm.getSimCountryIso();
        if (iso == null || iso.isEmpty()) iso = Locale.getDefault().getCountry();
        return iso.toUpperCase(Locale.ROOT);
    }

    /** A number for messages: the safe-list name, else the contact name, else the number. */
    static String describe(Context c, String number) {
        Entry e = match(c, number);
        if (e != null && !e.name.isEmpty()) return e.name;
        String contact = Contacts.nameFor(c, number);
        if (contact != null) return contact;
        return number == null || number.isEmpty() ? "a private number" : number;
    }
}
