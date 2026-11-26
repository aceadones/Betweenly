package com.nextlayer.betweenly

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException

/**
 * A newer build: version, the expected SHA-256 of the APK, and the release notes.
 * [apkUrl] is empty when the bytes come from the relay. A GitHub asset URL is
 * used only when that release is the one we chose. No user data.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val notes: String,
    val apkUrl: String = "",
) {
    fun identity(): String = "$versionCode|$versionName"

    /** Shown after "v". The name when we have one, otherwise the version code. */
    fun label(): String {
        val name = versionName.trim().removePrefix("v").removePrefix("V")
        return name.ifBlank { versionCode.toString() }
    }

    /**
     * Version code decides. A higher code is newer even when [versionName] matches
     * the installed name. A missing code falls back to the name, and an equal
     * name is not treated as newer.
     */
    fun isNewerThanInstalled(): Boolean {
        if (versionCode > 0) return versionCode > BuildConfig.VERSION_CODE
        return compareVersionNames(versionName, BuildConfig.VERSION_NAME) > 0
    }
}

/**
 * The update the UI is offering. The dialog and the banner share this so a
 * check on launch is visible on every tab.
 */
object UpdatePrompt {
    private val infoState = MutableStateFlow<UpdateInfo?>(null)
    val info: StateFlow<UpdateInfo?> = infoState
    private val dialogState = MutableStateFlow(false)
    val dialog: StateFlow<Boolean> = dialogState

    fun publish(info: UpdateInfo, dialog: Boolean) {
        infoState.value = info
        if (dialog) dialogState.value = true
    }

    fun hideDialog() {
        dialogState.value = false
    }

    fun clear() {
        infoState.value = null
        dialogState.value = false
    }
}

/**
 * [Updater.check] answer.
 * [UpToDate] includes "already checked within 6 hours" when [Updater.check]
 * was not forced, so a quiet caller does not treat that skip as a failure.
 * A saved offer from that check is still returned as [Available].
 */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object Error : UpdateCheck
}

/**
 * Checks the relay and, when the GitHub repo is public, the GitHub releases API.
 * Downloads the chosen APK, checks the SHA-256 and that it is signed with the
 * same certificate as this install, then hands it to the system installer.
 *
 * DATA-FLOW: version JSON and APK bytes, from the relay or GitHub, not encrypted,
 * no user data. The phone's IP is visible to whichever host answers. Nothing
 * about the pair is sent.
 */
object Updater {
    private const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
    private const val GITHUB_LATEST = "https://api.github.com/repos/aceadones/Betweenly/releases/latest"
    private const val APK_CAP = 80L * 1024L * 1024L

    /**
     * [UpdateCheck.UpToDate] when this build is current, nothing newer is published,
     * or a check already finished within 6 hours (unless [force]).
     * [UpdateCheck.Available] when the relay or a public GitHub release is newer.
     * [UpdateCheck.Error] when the relay could not be reached and GitHub did not
     * return a release. A failed call does not move the 6 hour clock. A 401,
     * 403, or 404 from GitHub is a private or empty repo: it is ignored, and it
     * is not a crash. It does not hide a relay failure.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateCheck {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - Store.lastUpdateCheck(app) < SIX_HOURS_MS) {
            val cached = readOffer(app)
            return if (cached != null && cached.isNewerThanInstalled()) {
                UpdateCheck.Available(cached)
            } else {
                UpdateCheck.UpToDate
            }
        }
        val (relay, github) = coroutineScope {
            val relayCall = async { relayCandidate() }
            val githubCall = async { githubCandidate() }
            relayCall.await() to githubCall.await()
        }
        if (!relay.reachable && !github.reachable) return UpdateCheck.Error
        val best = listOfNotNull(relay.info, github.info)
            .filter { it.isNewerThanInstalled() && shaOk(it.sha256) }
            .reduceOrNull { left, right -> prefer(left, right) }
        Store.setLastUpdateCheck(app, now)
        return if (best != null) {
            Store.setUpdateOffer(app, writeOffer(best))
            UpdateCheck.Available(best)
        } else {
            Store.setUpdateOffer(app, null)
            UpdateCheck.UpToDate
        }
    }

    /**
     * Downloads [info] into cacheDir/update.apk and returns it only when the
     * SHA-256 matches and the APK is signed by the same certificate as this
     * install. [onProgress] is called from a background thread with 0..1.
     */
    suspend fun downloadAndVerify(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val expected = hexBytes(info.sha256) ?: return@withContext null
        val dest = File(context.cacheDir, "update.apk")
        val ok = if (info.apkUrl.isBlank()) {
            // DATA-FLOW: APK bytes, from the relay, not encrypted, no user data.
            Relay.downloadApk(dest, onProgress)
        } else {
            // DATA-FLOW: APK bytes from the GitHub release named by the check.
            // Not encrypted, no user data. Redirects are limited to GitHub hosts.
            downloadHttps(info.apkUrl, dest, onProgress)
        }
        if (!ok || !dest.isFile) return@withContext null
        val actual = sha256(dest)
        if (!MessageDigest.isEqual(actual, expected)) {
            dest.delete()
            return@withContext null
        }
        if (!sameSigner(context, dest)) {
            dest.delete()
            return@withContext null
        }
        dest
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

    private data class Source(val reachable: Boolean, val info: UpdateInfo?)

    private suspend fun relayCandidate(): Source {
        val query = try {
            Relay.queryLatest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Source(false, null)
        }
        val reachable = query.httpCode == 200 || query.httpCode == 404
        if (query.httpCode != 200) return Source(reachable, null)
        val json = query.json ?: return Source(true, null)
        val code = json.optInt("versionCode", 0)
        val sha = json.optString("sha256").trim().lowercase()
        if (code <= 0 || !shaOk(sha)) return Source(true, null)
        return Source(
            true,
            UpdateInfo(
                versionCode = code,
                versionName = json.optString("versionName"),
                sha256 = sha,
                notes = json.optString("notes").take(2000),
            ),
        )
    }

    private suspend fun githubCandidate(): Source = withContext(Dispatchers.IO) {
        val response = try {
            // DATA-FLOW: public GitHub release JSON for aceadones/Betweenly.
            // No pair data. 401, 403, and 404 (private repo or no release) are ignored.
            getText(GITHUB_LATEST, 512 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext Source(false, null)
        }
        when (response.code) {
            // Private repo or no release. Not a crash, and not proof that we are up to date.
            401, 403, 404 -> return@withContext Source(false, null)
            200 -> Unit
            else -> return@withContext Source(false, null)
        }
        val body = response.body ?: return@withContext Source(false, null)
        val json = try {
            JSONObject(body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext Source(true, null)
        }
        try {
            parseGithub(json)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Source(true, null)
        }
    }

    private fun parseGithub(json: JSONObject): Source {
        val tagged = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        val named = json.optString("name").trim().removePrefix("v").removePrefix("V")
        var versionName = tagged.ifBlank { named }
        var notes = json.optString("body").take(2000)
        var code = 0
        var apkUrl = ""
        var sha = ""
        var latestJson = ""
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!allowedHttps(url)) continue
                if (assetName.equals("latest.json", ignoreCase = true)) latestJson = url
                val apk = assetName.endsWith(".apk", ignoreCase = true)
                if (!apk) continue
                val namedApp = assetName.contains("Betweenly", ignoreCase = true)
                if (apkUrl.isEmpty() || namedApp) {
                    apkUrl = url
                    sha = parseSha(asset.optString("digest")).orEmpty()
                }
            }
        }
        if (latestJson.isNotEmpty()) {
            // DATA-FLOW: optional latest.json on that GitHub release (versionCode, sha256, notes). No pair data.
            val extra = getText(latestJson, 64 * 1024)
            if (extra.code == 200 && !extra.body.isNullOrEmpty()) {
                try {
                    val doc = JSONObject(extra.body)
                    val published = doc.optInt("versionCode", 0)
                    if (published > 0) code = published
                    val publishedName = doc.optString("versionName").trim().removePrefix("v").removePrefix("V")
                    if (publishedName.isNotEmpty()) versionName = publishedName
                    val publishedSha = parseSha(doc.optString("sha256"))
                    if (!publishedSha.isNullOrEmpty()) sha = publishedSha
                    val publishedNotes = doc.optString("notes")
                    if (publishedNotes.isNotBlank()) notes = publishedNotes.take(2000)
                } catch (_: Exception) {
                    // The asset digest can still identify the APK.
                }
            }
        }
        if (apkUrl.isEmpty() || !shaOk(sha)) return Source(true, null)
        return Source(
            true,
            UpdateInfo(
                versionCode = code,
                versionName = versionName,
                sha256 = sha,
                notes = notes,
                apkUrl = apkUrl,
            ),
        )
    }

    /** Higher versionCode wins. Equal codes keep [left]. A missing code loses to a real one when the names match. */
    private fun prefer(left: UpdateInfo, right: UpdateInfo): UpdateInfo {
        if (left.versionCode > 0 && right.versionCode > 0 && left.versionCode != right.versionCode) {
            return if (right.versionCode > left.versionCode) right else left
        }
        val byName = compareVersionNames(right.versionName, left.versionName)
        if (byName > 0) return right
        if (byName < 0) return left
        return if (right.versionCode > left.versionCode) right else left
    }

    private data class HttpText(val code: Int, val body: String?)

    private fun getText(url: String, max: Int): HttpText {
        if (!allowedHttps(url)) return HttpText(-1, null)
        var current = url
        repeat(5) {
            val conn = open(current) ?: return HttpText(-1, null)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return HttpText(code, null)
                    val next = URL(URL(current), loc).toString()
                    if (!allowedHttps(next)) return HttpText(code, null)
                    current = next
                    return@repeat
                }
                if (code != 200) return HttpText(code, null)
                val text = conn.inputStream.use { readText(it, max) } ?: return HttpText(-1, null)
                return HttpText(200, text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return HttpText(-1, null)
            } finally {
                conn.disconnect()
            }
        }
        return HttpText(-1, null)
    }

    private fun downloadHttps(url: String, dest: File, onProgress: (Float) -> Unit): Boolean {
        if (!allowedHttps(url)) return false
        var current = url
        var conn: HttpsURLConnection? = null
        try {
            repeat(5) {
                val opened = open(current) ?: return false
                conn = opened
                opened.setRequestProperty("Accept-Encoding", "identity")
                val code = opened.responseCode
                if (code in 300..399) {
                    val loc = opened.getHeaderField("Location") ?: return false
                    val next = URL(URL(current), loc).toString()
                    opened.disconnect()
                    conn = null
                    if (!allowedHttps(next)) return false
                    current = next
                    return@repeat
                }
                if (code !in 200..299) return false
                val total = opened.contentLengthLong
                if (total > APK_CAP) return false
                val part = File(dest.parentFile, dest.name + ".part")
                onProgress(0f)
                opened.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(8192)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > APK_CAP) {
                                part.delete()
                                return false
                            }
                            out.write(buf, 0, n)
                            if (total > 0L) onProgress((read.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
                if (!part.isFile || part.length() <= 0L) {
                    part.delete()
                    return false
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                try {
                    onProgress(1f)
                } catch (_: Exception) {
                    // The file is complete. Progress must not fail the download.
                }
                return true
            }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            File(dest.parentFile, dest.name + ".part").delete()
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String): HttpsURLConnection? {
        if (!allowedHttps(url)) return null
        return try {
            val connection = URL(url).openConnection()
            if (connection !is HttpsURLConnection) return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Betweenly/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, application/octet-stream")
            connection
        } catch (_: Exception) {
            null
        }
    }

    private fun allowedHttps(url: String): Boolean {
        val host = try {
            val parsed = URL(url)
            if (!parsed.protocol.equals("https", ignoreCase = true)) return false
            parsed.host.lowercase()
        } catch (_: Exception) {
            return false
        }
        return host == "github.com" ||
            host == "api.github.com" ||
            host == "release-assets.githubusercontent.com" ||
            host == "objects.githubusercontent.com" ||
            host == "github-releases.githubusercontent.com"
    }

    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val mine = readPackage(pm, context.packageName, null) ?: return false
        val other = readPackage(pm, null, apk.absolutePath) ?: return false
        val installed = certs(mine)
        val incoming = certs(other)
        if (installed.isEmpty() || incoming.isEmpty() || installed.size != incoming.size) return false
        val left = installed.toMutableList()
        for (sig in incoming) {
            val index = left.indexOfFirst { MessageDigest.isEqual(it, sig) }
            if (index < 0) return false
            left.removeAt(index)
        }
        return left.isEmpty()
    }

    private fun readPackage(pm: PackageManager, pkg: String?, archive: String?): PackageInfo? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val wrapped = PackageManager.PackageInfoFlags.of(flags.toLong())
                if (archive != null) pm.getPackageArchiveInfo(archive, wrapped) else pm.getPackageInfo(pkg!!, wrapped)
            } else {
                @Suppress("DEPRECATION")
                if (archive != null) {
                    pm.getPackageArchiveInfo(archive, flags)
                } else {
                    pm.getPackageInfo(pkg!!, flags)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo): List<ByteArray> {
        val signing = info.signingInfo
        val modern = signing?.apkContentsSigners
        if (modern != null && modern.isNotEmpty()) return modern.map { it.toByteArray() }
        val history = signing?.signingCertificateHistory
        if (history != null && history.isNotEmpty()) return listOf(history.last().toByteArray())
        val legacy = info.signatures
        if (legacy != null && legacy.isNotEmpty()) return legacy.map { it.toByteArray() }
        return emptyList()
    }

    private fun readText(input: java.io.InputStream, max: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= max && input.read() >= 0) return null
        if (total == 0) return null
        return out.toString(Charsets.UTF_8.name())
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
        val bytes = parseSha(hex) ?: return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = bytes[i * 2].digitToIntOrNull(16) ?: return null
            val lo = bytes[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun parseSha(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().lowercase().removePrefix("sha256:")
        if (trimmed.length != 64) return null
        if (trimmed.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return trimmed
    }

    private fun shaOk(hex: String): Boolean = parseSha(hex) != null

    private fun writeOffer(info: UpdateInfo): String = JSONObject()
        .put("versionCode", info.versionCode)
        .put("versionName", info.versionName)
        .put("sha256", info.sha256)
        .put("notes", info.notes)
        .put("apkUrl", info.apkUrl)
        .toString()

    private fun readOffer(context: Context): UpdateInfo? {
        val raw = Store.updateOffer(context) ?: return null
        return try {
            val json = JSONObject(raw)
            val info = UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName"),
                sha256 = json.optString("sha256"),
                notes = json.optString("notes"),
                apkUrl = json.optString("apkUrl"),
            )
            if (shaOk(info.sha256)) info else null
        } catch (_: Exception) {
            null
        }
    }
}

/** Numeric dot-separated names. "0.0.10" is newer than "0.0.9". A leading v is ignored. */
internal fun compareVersionNames(left: String, right: String): Int {
    fun parts(raw: String) = raw.trim().removePrefix("v").removePrefix("V")
        .split('.', '-', '+')
        .filter { it.isNotEmpty() }
    val a = parts(left)
    val b = parts(right)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val asPart = a.getOrElse(i) { "0" }
        val bsPart = b.getOrElse(i) { "0" }
        val an = asPart.toIntOrNull()
        val bn = bsPart.toIntOrNull()
        if (an != null && bn != null) {
            if (an != bn) return an.compareTo(bn)
        } else {
            val cmp = asPart.compareTo(bsPart, ignoreCase = true)
            if (cmp != 0) return cmp
        }
    }
    return 0
}
