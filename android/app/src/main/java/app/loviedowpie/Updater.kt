package app.loviedowpie

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * A newer build published on the relay: version, the expected SHA-256 of the
 * APK, and the release notes. No user data.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val notes: String,
)

/**
 * [Updater.check] answer.
 * [UpToDate] includes "already checked within 24 hours" when [Updater.check]
 * was not forced, so a quiet caller does not treat that skip as a failure.
 */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object Error : UpdateCheck
}

/**
 * Checks the relay for a newer APK, downloads it, checks the hash, and hands
 * it to the system installer. The user has to allow "install unknown apps"
 * once; [install] opens that screen when it is still off and returns false
 * so the UI can call [install] again after they allow it.
 *
 * DATA-FLOW: version JSON and APK bytes, from the relay, not encrypted, no
 * user data. The phone's IP is visible to the relay. Nothing about the pair
 * is sent.
 */
object Updater {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * [UpdateCheck.UpToDate] when this build is current, nothing is published,
     * or a check already succeeded within 24 hours (unless [force]).
     * [UpdateCheck.Available] when the relay has a newer APK.
     * [UpdateCheck.Error] when the relay could not be reached. A failed call
     * does not move the 24 hour clock, so the next open can try again. The
     * home auto-check should stay quiet on [UpdateCheck.UpToDate] and
     * [UpdateCheck.Error]. A real answer (including "nothing published") does
     * move the clock.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateCheck {
        val now = System.currentTimeMillis()
        if (!force && now - Store.lastUpdateCheck(context) < DAY_MS) return UpdateCheck.UpToDate
        // DATA-FLOW: update metadata, from the relay, not encrypted, no user data.
        val query = Relay.queryLatest()
        if (query.httpCode != 200 && query.httpCode != 404) return UpdateCheck.Error
        Store.setLastUpdateCheck(context, now)
        if (query.httpCode == 404) return UpdateCheck.UpToDate
        val json = query.json ?: return UpdateCheck.UpToDate
        val code = json.optInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) return UpdateCheck.UpToDate
        return UpdateCheck.Available(
            UpdateInfo(
                versionCode = code,
                versionName = json.optString("versionName"),
                sha256 = json.optString("sha256"),
                notes = json.optString("notes"),
            ),
        )
    }

    /**
     * Downloads the current published APK into cacheDir/update.apk and returns
     * it only when the SHA-256 matches the relay's metadata. [onProgress] is
     * called from a background thread with values from 0 to 1.
     */
    suspend fun downloadAndVerify(context: Context, onProgress: (Float) -> Unit): File? {
        // DATA-FLOW: update metadata (for the expected hash), from the relay, not encrypted, no user data.
        val json = Relay.updateLatest() ?: return null
        val expected = hexBytes(json.optString("sha256")) ?: return null
        val dest = File(context.cacheDir, "update.apk")
        // DATA-FLOW: APK bytes, from the relay, not encrypted, no user data.
        if (!Relay.downloadApk(dest, onProgress)) return null
        val actual = sha256(dest)
        if (!MessageDigest.isEqual(actual, expected)) {
            dest.delete()
            return null
        }
        return dest
    }

    /**
     * Starts the system package installer for [file]. Returns false when the
     * "install unknown apps" screen was opened instead, or when no activity
     * could handle the intent. Returns true when the installer was opened.
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                app.startActivity(settings)
                false
            } catch (_: Exception) {
                false
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }

    private fun hexBytes(hex: String): ByteArray? {
        val s = hex.trim().lowercase()
        if (s.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = s[i * 2].digitToIntOrNull(16) ?: return null
            val lo = s[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
