import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal desktop "parent" for testing, using only the JDK.
 *   java tools/ParentCli.java send '{"t":"status","full":true}'
 *   java tools/ParentCli.java read 10m        # decrypt recent messages
 *   java tools/ParentCli.java newsecret       # print a fresh 32-byte secret
 * Reads secrets/devices/$DEVICE/pairing.properties (server, topic, secret).
 */
public class ParentCli {
    static final byte[] AAD = "phonelock/1".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] a) throws Exception {
        if (a[0].equals("newsecret")) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            System.out.println(Base64.getUrlEncoder().withoutPadding().encodeToString(k));
            return;
        }
        var p = new java.util.Properties();
        // DEVICE=<slug> picks secrets/devices/<slug>/pairing.properties
        String dev = System.getenv().getOrDefault("DEVICE", "kid");
        p.load(Files.newBufferedReader(Path.of("secrets/devices/" + dev + "/pairing.properties")));
        String server = p.getProperty("server"), topic = p.getProperty("topic");
        byte[] key = Base64.getUrlDecoder().decode(p.getProperty("secret"));
        HttpClient http = HttpClient.newHttpClient();
        if (a[0].equals("send")) {
            String json = a[1];
            byte[] id = new byte[9];
            new SecureRandom().nextBytes(id);
            json = json.replaceFirst("\\{", "{\"v\":1,\"id\":\"" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(id) + "\",\"ts\":" + System.currentTimeMillis() + ",\"from\":\"PC\",");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[12 + ct.length];
            System.arraycopy(iv, 0, all, 0, 12);
            System.arraycopy(ct, 0, all, 12, ct.length);
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(all);
            var r = http.send(HttpRequest.newBuilder(URI.create(server + "/" + topic))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            System.out.println("sent " + r.statusCode() + ": " + json);
        } else if (a[0].equals("read")) {
            var r = http.send(HttpRequest.newBuilder(URI.create(server + "/" + topic + "/json?poll=1&since="
                    + (a.length > 1 ? a[1] : "10m"))).build(), HttpResponse.BodyHandlers.ofString());
            for (String line : r.body().split("\n")) {
                int i = line.indexOf("\"message\":\"");
                if (i < 0) continue;
                String msg = line.substring(i + 11, line.indexOf('"', i + 11));
                try {
                    byte[] all = Base64.getUrlDecoder().decode(msg);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
                    c.updateAAD(AAD);
                    System.out.println(new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    System.out.println("(not ours) " + msg);
                }
            }
        }
    }
}
