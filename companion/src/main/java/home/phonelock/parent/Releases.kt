package home.phonelock.parent

import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * Downloads from the public PhoneLock releases on GitHub, and checks of what was downloaded. Used by
 * phone-to-phone setup (the lock app it installs) and by this app's own updates (GitHub build only).
 */
object Releases {
    /** Files of the newest release; GitHub redirects these to the actual download. */
    const val LATEST = "https://github.com/RyanEwen/phonelock/releases/latest/download/"

    /** What an APK says it is: package name, version, and its signing certificate (see [certHash]). */
    data class ApkInfo(val packageName: String, val versionCode: Long, val signer: String?)

    /**
     * Downloads [url] into [into], replacing it, and returns the SHA-256 of what was saved (lowercase hex).
     * Throws [FileNotFoundException] when the release has no such file (HTTP 404), and [IOException] for any
     * other failure, including a file larger than [maxBytes]. [onProgress] gets 0..1 when the size is known.
     * Blocks; call off the main thread.
     */
    fun download(url: String, into: File, maxBytes: Long, onProgress: (Float) -> Unit = {}): String {
        val h = URL(url).openConnection() as HttpURLConnection
        h.instanceFollowRedirects = true
        h.connectTimeout = 20_000
        h.readTimeout = 60_000
        val md = MessageDigest.getInstance("SHA-256")
        try {
            when (val code = h.responseCode) {
                200 -> {}
                404 -> throw FileNotFoundException(url)
                else -> throw IOException("Download failed (HTTP $code)")
            }
            val total = h.contentLengthLong
            if (total > maxBytes) throw IOException("Download is too large")

            var done = 0L
            var shown = -1f
            h.inputStream.use { input ->
                into.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > maxBytes) throw IOException("Download is too large")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)

                        // Report in steps of 2% so a fast download doesn't flood the UI.
                        val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (total > 0 && f - shown >= 0.02f) {
                            shown = f
                            onProgress(f)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            into.delete()
            throw e
        } finally {
            h.disconnect()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reads [apk]'s package name, version and signer, or null if it isn't a readable APK. */
    fun inspect(pm: PackageManager, apk: File): ApkInfo? {
        val pi = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        return ApkInfo(pi.packageName, pi.longVersionCode, onlySigner(pi.signingInfo?.apkContentsSigners))
    }

    /** The signer of installed package [pkg] in the same form as [ApkInfo.signer], or null if unknown. */
    fun installedSigner(pm: PackageManager, pkg: String): String? = runCatching {
        onlySigner(pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners)
    }.getOrNull()

    /** The single signing certificate's hash; null when there is none or more than one (never expected). */
    private fun onlySigner(certs: Array<Signature>?): String? = certs?.singleOrNull()?.let(::certHash)

    /** URL-safe base64 SHA-256 of a signing certificate, the form [Setup.LOCK_SIGNATURE_SHA256] is written in. */
    private fun certHash(s: Signature): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
