package home.phonelock;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The child's contacts, for parents: list them (to pick safe numbers), add one, remove one. New contacts
 * go into the device's Google account when there is one, so they sync like contacts added on the device.
 */
final class Contacts {
    private Contacts() {}

    private static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Display name of the contact with {@code number}, or null. */
    static String nameFor(Context c, String number) {
        if (number == null || number.isEmpty() || !canRead(c)) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** All contacts with a phone number: [{id, name, numbers: [..]}], sorted by name. */
    static JSONArray list(Context c) throws Exception {
        Map<Long, JSONObject> byId = new LinkedHashMap<>();
        if (!canRead(c)) return new JSONArray();
        String[] cols = {ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY};
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE")) {
            while (cur != null && cur.moveToNext()) {
                long id = cur.getLong(0);
                JSONObject o = byId.get(id);
                if (o == null) {
                    // The lookup key survives Android re-merging contacts; the numeric id may not.
                    o = new JSONObject().put("key", cur.getString(3)).put("name", cur.getString(1))
                            .put("numbers", new JSONArray());
                    byId.put(id, o);
                }
                JSONArray nums = o.getJSONArray("numbers");
                String n = cur.getString(2);
                boolean dup = false;
                for (int i = 0; i < nums.length(); i++) if (nums.getString(i).equals(n)) dup = true;
                if (!dup && n != null) nums.put(n);
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : byId.values()) out.put(o);
        return out;
    }

    /** Adds a contact {@code name} with mobile {@code number}. Returns false if it couldn't be saved. */
    static boolean add(Context c, String name, String number) {
        Account google = null;
        try {
            Account[] accounts = AccountManager.get(c).getAccountsByType("com.google");
            if (accounts.length > 0) google = accounts[0];
        } catch (SecurityException ignored) {}
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, google == null ? null : google.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, google == null ? null : google.name)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());
        try {
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            Log.w(Policy.TAG, "add contact: " + e);
            return false;
        }
    }

    /** Deletes the contact with lookup key {@code key}. Returns its name, or null if it wasn't found. */
    static String remove(Context c, String key) {
        if (key == null || key.isEmpty()) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(key));
        String name = null;
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {}
        if (name == null) return null;
        try {
            return c.getContentResolver().delete(uri, null, null) > 0 ? name : null;
        } catch (Exception e) {
            Log.w(Policy.TAG, "remove contact: " + e);
            return null;
        }
    }

    /** Replies to a {@code contacts} request (or after a change) with the full list. */
    static void sendList(Context c, String req) {
        new Thread(() -> {
            try {
                JSONObject o = Remote.msg("contacts");
                if (req != null) o.put("req", req);
                o.put("list", list(c));
                if (!canRead(c)) o.put("error", "The device hasn't allowed reading its contacts");
                Remote.publish(c, o, null);
            } catch (Exception e) {
                Log.w(Policy.TAG, "contacts: " + e);
            }
        }, "contacts").start();
    }
}
