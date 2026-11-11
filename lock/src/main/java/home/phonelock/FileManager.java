package home.phonelock;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Parent file management for the device's shared storage (Download, Pictures, DCIM, Music, Documents, …):
 * list, download, upload, delete, new folder and rename. App-private data ("Android/") and anything outside
 * shared storage are out of reach. Needs "All files access" (MANAGE_EXTERNAL_STORAGE), granted by
 * tools/configure-lock.sh or in parent mode. Files travel as encrypted attachments of at most
 * {@link #MAX_BYTES}.
 */
final class FileManager {
    /** Largest file sent either way; the hub accepts attachments up to 50 MB. */
    static final long MAX_BYTES = 48L * 1024 * 1024;

    private FileManager() {}

    private static File root() {
        return Environment.getExternalStorageDirectory();
    }

    /**
     * The file for parent-supplied {@code path} (relative to shared storage), or null if it escapes shared
     * storage or points into app-private "Android/".
     */
    static File resolve(String path) {
        try {
            File base = root().getCanonicalFile();
            File f = new File(base, path == null ? "" : path).getCanonicalFile();
            String b = base.getPath(), p = f.getPath();
            if (!p.equals(b) && !p.startsWith(b + "/")) return null;
            String rel = p.equals(b) ? "" : p.substring(b.length() + 1);
            // Shared storage is case-insensitive, so "android/…" reaches the same folder.
            String lower = rel.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("android") || lower.startsWith("android/")) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** Path of {@code f} relative to shared storage, using canonical paths on both sides. */
    private static String rel(File f) {
        try {
            String b = root().getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.equals(b)) return "";
            return p.startsWith(b + "/") ? p.substring(b.length() + 1) : f.getName();
        } catch (java.io.IOException e) {
            return f.getName();
        }
    }

    static boolean permitted() {
        return Environment.isExternalStorageManager();
    }

    /**
     * Runs a file operation from a parent: {@code ls}, {@code get}, {@code rm}, {@code mkdir}, {@code mv}
     * ({@code to}: new name in the same folder) or {@code put} (with the attachment at {@code attachmentUrl}).
     * Replies with {@code files} (the folder's listing afterwards) or {@code file} (a download). Runs on its
     * own thread.
     */
    static void handle(Context c, JSONObject m, String who, String attachmentUrl) {
        WORKER.execute(() -> {
            String op = m.optString("op");
            String req = m.optString("id");
            String path = m.optString("path");
            try {
                if (!permitted()) {
                    reply(c, req, op, path, "The device hasn't allowed file access yet", null);
                    return;
                }
                File f = resolve(path);
                if (f == null) {
                    reply(c, req, op, path, "That location isn't available", null);
                    return;
                }
                switch (op) {
                    case "ls":
                        reply(c, req, op, rel(f), null, f);
                        break;
                    case "get":
                        sendFile(c, req, f);
                        break;
                    case "mkdir":
                        if (!f.mkdirs() && !f.isDirectory()) throw new Exception("Couldn't create the folder");
                        log(c, who, "created folder " + rel(f));
                        reply(c, req, op, rel(f), null, f.getParentFile());
                        break;
                    case "rm":
                        // The storage root and its top-level folders (DCIM, Download, Music…) stay.
                        File parentDir = f.getParentFile();
                        if (f.equals(root().getCanonicalFile())
                                || (parentDir != null && parentDir.equals(root().getCanonicalFile()) && f.isDirectory())) {
                            throw new Exception("Top-level folders can't be deleted; open it and delete what's inside");
                        }
                        deleteTree(f);
                        scan(c, f);
                        log(c, who, "deleted " + rel(f));
                        reply(c, req, op, rel(f), null, f.getParentFile());
                        break;
                    case "mv": {
                        String to = m.optString("to").trim();
                        if (to.isEmpty() || to.contains("/")) throw new Exception("Not a valid name");
                        File dest = resolve(rel(f.getParentFile()) + "/" + to);
                        if (dest == null || dest.exists()) throw new Exception("That name is taken");
                        if (!f.renameTo(dest)) throw new Exception("Couldn't rename it");
                        scan(c, f);
                        scan(c, dest);
                        log(c, who, "renamed " + rel(f) + " to " + to);
                        reply(c, req, op, rel(dest), null, dest.getParentFile());
                        break;
                    }
                    case "put": {
                        // path is the folder; the name comes with the upload.
                        String name = m.optString("name").trim();
                        if (name.isEmpty() || name.contains("/")) throw new Exception("Not a valid name");
                        File dest = freeName(resolve(rel(f) + "/" + name));
                        if (dest == null) throw new Exception("That location isn't available");
                        if (attachmentUrl == null) throw new Exception("No file came with the upload");
                        receive(c, attachmentUrl, m.optString("id"), dest);
                        scan(c, dest);
                        log(c, who, "added " + rel(dest));
                        reply(c, req, op, rel(dest), null, f);
                        break;
                    }
                    default:
                        break;
                }
            } catch (Throwable e) {
                // Throwable, so even running out of memory reports back instead of killing the lock app.
                Log.w(Policy.TAG, "files " + op + ": " + e);
                reply(c, req, op, path, e.getMessage() == null ? "Something went wrong" : e.getMessage(), null);
            }
        });
    }

    /**
     * File operations and transfers run on their own thread, so a large upload or download never holds up
     * the device's other messages (states, notices, location).
     */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Child gets a notice and parents a list entry for each change, like other parent actions. */
    private static void log(Context c, String who, String what) {
        Remote.notifyKid(c, who + " " + what);
        Remote.event(c, who + " " + what, who, false);
    }

    /** Deletes {@code f} and, for a real folder, its contents; links are removed, never followed. */
    private static void deleteTree(File f) throws Exception {
        boolean link = java.nio.file.Files.isSymbolicLink(f.toPath());
        File[] kids = !link && f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete() && f.exists()) throw new Exception("Couldn't delete " + f.getName());
    }

    /** Tells the media index about a changed path, so galleries and music players update. */
    private static void scan(Context c, File f) {
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
    }

    /** {@code want}, or "name (1).ext", "name (2).ext", … if that's taken, so uploads never overwrite. */
    private static File freeName(File want) {
        if (want == null || !want.exists()) return want;
        String n = want.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File f = new File(want.getParentFile(), base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return null;
    }

    /**
     * Streams an uploaded file from the hub, decrypting piece by piece into a hidden temporary file that is
     * renamed into place only when complete, so a failure never leaves a broken file behind.
     */
    private static void receive(Context c, String url, String msgId, File dest) throws Exception {
        if (!url.startsWith(RemoteService.server(c) + "/")) throw new Exception("Upload came from an unknown server");
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(parent, "." + dest.getName() + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20_000);
        h.setReadTimeout(120_000);
        try (InputStream in = new java.io.BufferedInputStream(h.getInputStream());
             FileOutputStream out = new FileOutputStream(tmp)) {
            BlobStream.open(Crypto.key(Remote.secret(c)), "phonelock/file/" + msgId, in, out, MAX_BYTES);
        } catch (Throwable e) {
            tmp.delete();
            throw e;
        } finally {
            h.disconnect();
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new Exception("Couldn't save the file");
        }
    }

    /**
     * Streams {@code f} to the hub as an encrypted attachment of a {@code file} message, piece by piece, so
     * even a 48 MB file needs only about 1 MB of memory.
     */
    private static void sendFile(Context c, String req, File f) throws Exception {
        if (!f.isFile()) throw new Exception("Not a file");
        long size = f.length();
        if (size > MAX_BYTES) throw new Exception("Too large to send (over 48 MB)");
        JSONObject o = Remote.msg("file");
        o.put("req", req).put("path", rel(f)).put("name", f.getName()).put("size", size);
        byte[] key = Crypto.key(Remote.secret(c));
        HttpURLConnection h = (HttpURLConnection) new URL(
                RemoteService.server(c) + "/" + RemoteService.topic(c)).openConnection();
        h.setRequestMethod("PUT");
        h.setDoOutput(true);
        h.setConnectTimeout(20_000);
        h.setReadTimeout(120_000);
        h.setRequestProperty("Filename", "blob.bin");
        h.setRequestProperty("Message", Crypto.seal(key, o.toString()));
        h.setFixedLengthStreamingMode(BlobStream.sealedSize(size));
        try (InputStream in = new java.io.FileInputStream(f);
             java.io.OutputStream out = new java.io.BufferedOutputStream(h.getOutputStream())) {
            BlobStream.seal(key, "phonelock/file/" + o.getString("id"), in, size, out);
        }
        int code = h.getResponseCode();
        h.disconnect();
        if (code < 200 || code > 299) throw new Exception("Upload to the hub failed (HTTP " + code + ")");
    }

    /** Publishes a {@code files} reply; with {@code dir}, includes its listing (folders first, by name). */
    private static void reply(Context c, String req, String op, String path, String error, File dir) {
        try {
            JSONObject o = Remote.msg("files");
            o.put("req", req).put("op", op).put("path", path);
            if (error != null) o.put("error", error);
            if (dir != null) {
                o.put("dir", rel(dir));
                o.put("entries", listing(dir));
            }
            Remote.publish(c, o, null);
        } catch (Exception e) {
            Log.w(Policy.TAG, "files reply: " + e);
        }
    }

    private static JSONArray listing(File dir) throws Exception {
        JSONArray out = new JSONArray();
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        Arrays.sort(kids, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        boolean atRoot = dir.getAbsolutePath().equals(root().getAbsolutePath());
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (atRoot && k.getName().equals("Android")) continue;
            out.put(new JSONObject().put("name", k.getName()).put("dir", k.isDirectory())
                    .put("size", k.isFile() ? k.length() : 0).put("mtime", k.lastModified()));
        }
        return out;
    }
}
