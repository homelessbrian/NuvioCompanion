package com.homelessbrian.nuviocompanion.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.homelessbrian.nuviocompanion.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps Nuvio Launch up to date from GitHub Releases.
 *
 * Every few hours (when an app screen or the drawer opens) it asks GitHub for the latest release.
 * When that release is newer than the installed app, [available] is set and the update banner
 * appears. "Download & install" downloads the release APK and hands it to Android's installer,
 * which shows its own "Update this app?" prompt.
 *
 * The test (debug) copy never updates itself: releases are the regular app, which is a separate
 * app on the TV.
 */
object AppUpdater {
    private const val REPO = "homelessbrian/NuvioLaunch"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val PREFS = "app_updates"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000
    private const val SNOOZE_MS = 24L * 60 * 60 * 1000
    private const val APK_MIME = "application/vnd.android.package-archive"

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        /** [fraction] is null when GitHub didn't say how big the file is. */
        data class Downloading(val fraction: Float?) : Status
        /** Downloaded; waiting for "Install now" (or for the install permission). */
        data object Ready : Status
        /** Android needs "Install unknown apps" turned on for Nuvio Launch. */
        data object NeedsPermission : Status
        /** Android's install prompt is on screen. */
        data object Installing : Status
        data class Failed(val message: String) : Status
    }

    /** False for the test (debug) copy. */
    val supported: Boolean = !BuildConfig.DEBUG

    /** The newer release, or null when the app is up to date (or nothing is known yet). */
    var available by mutableStateOf<Release?>(null)
        private set
    var status by mutableStateOf<Status>(Status.Idle)
        private set
    /** Wall-clock time of the last finished check, 0 if never. */
    var lastChecked by mutableStateOf(0L)
        private set
    /** Bumped when the banner is snoozed, so banners redraw. */
    var snoozeTick by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var installerOpenedAt = 0L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun updatesDir(context: Context) = File(context.applicationContext.cacheDir, "updates")
    private fun apkFile(context: Context, release: Release) = File(updatesDir(context), "NuvioLaunch-${release.version}.apk")

    // ---- Settings ---------------------------------------------------------------------------

    fun autoCheck(context: Context): Boolean = prefs(context).getBoolean("auto_check", true)
    fun setAutoCheck(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_check", value).apply()
        if (value) maybeCheck(context)
    }

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    // ---- Banner -------------------------------------------------------------------------------

    /** Whether the update banner should show right now. */
    fun bannerVisible(context: Context): Boolean {
        if (snoozeTick < 0) return false // read so Compose redraws after "Later"
        val release = available ?: return false
        if (!supported) return false
        // A download or the install prompt in progress always shows; everything else respects "Later".
        if (status is Status.Downloading || status == Status.Installing || status == Status.NeedsPermission) return true
        val p = prefs(context)
        val snoozed = p.getString("snooze_version", null) == release.version && System.currentTimeMillis() < p.getLong("snooze_until", 0L)
        return !snoozed
    }

    /** "Later": hide the banner for this version for a day. The update stays in Settings → Updates. */
    fun snooze(context: Context) {
        val release = available ?: return
        prefs(context).edit()
            .putString("snooze_version", release.version)
            .putLong("snooze_until", System.currentTimeMillis() + SNOOZE_MS)
            .apply()
        if (status is Status.Failed || status == Status.NeedsPermission) status = Status.Idle
        snoozeTick++
    }

    // ---- Checking -----------------------------------------------------------------------------

    /** Loads what the last check found (so the banner shows straight away), then checks GitHub if it's been a while. */
    fun maybeCheck(context: Context) {
        if (!supported) return
        loadSaved(context)
        if (!autoCheck(context)) return
        val last = prefs(context).getLong("last_checked", 0L)
        val now = System.currentTimeMillis()
        if (now - last >= CHECK_EVERY_MS || now < last) check(context)
    }

    /** Asks GitHub now ("Check for updates"). */
    fun checkNow(context: Context) {
        if (!supported) return
        loadSaved(context)
        check(context)
    }

    private fun loadSaved(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        lastChecked = p.getLong("last_checked", 0L)
        val saved = runCatching {
            p.getString("release", null)?.let { json ->
                val o = JSONObject(json)
                Release(o.getString("version"), o.optString("notes"), o.getString("url"), o.optLong("size"))
            }
        }.getOrNull()
        available = saved?.takeIf { isNewer(it.version, installedVersion(context)) }
        if (available == null) clearDownloads(context)
        else if (apkFile(context, available!!).isFile && status == Status.Idle) status = Status.Ready
    }

    private fun check(context: Context) {
        if (checkJob?.isActive == true) return
        val app = context.applicationContext
        val busy = status !is Status.Idle && status !is Status.Failed
        if (!busy) status = Status.Checking
        checkJob = scope.launch {
            val result = runCatching { fetchLatest() }
            withContext(Dispatchers.Main) {
                val now = System.currentTimeMillis()
                result.onSuccess { release ->
                    lastChecked = now
                    prefs(app).edit().putLong("last_checked", now).apply()
                    val newer = release?.takeIf { isNewer(it.version, installedVersion(app)) }
                    if (newer?.version != available?.version) {
                        // A different release than before: forget any older download.
                        downloadJob?.cancel()
                        clearDownloads(app)
                        if (status !is Status.Checking) status = Status.Idle
                    }
                    available = newer
                    val editor = prefs(app).edit()
                    if (newer == null) {
                        editor.remove("release")
                    } else {
                        val saved = JSONObject().put("version", newer.version).put("notes", newer.notes).put("url", newer.apkUrl).put("size", newer.sizeBytes)
                        editor.putString("release", saved.toString())
                    }
                    editor.apply()
                    if (newer == null) clearDownloads(app)
                    if (status is Status.Checking) status = if (newer != null && apkFile(app, newer).isFile) Status.Ready else Status.Idle
                }
                result.onFailure {
                    if (status is Status.Checking) status = Status.Idle
                    // A failed check stays quiet; the next one tries again. Settings shows "Couldn't reach GitHub".
                    lastCheckFailed = true
                }
                if (result.isSuccess) lastCheckFailed = false
            }
        }
    }

    /** True when the last check couldn't reach GitHub. Shown in Settings → Updates only. */
    var lastCheckFailed by mutableStateOf(false)
        private set

    /** The latest published release, or null if there is none or it has no APK. */
    private fun fetchLatest(): Release? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NuvioLaunch-updater")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null // no releases yet
            if (code !in 200..299) error("GitHub answered $code")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (parseVersion(version) == null) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                // Prefer the stable name; otherwise take the first APK.
                if (apkUrl == null || name == "NuvioLaunch.apk") {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    size = asset.optLong("size")
                }
            }
            return apkUrl?.let { Release(version, cleanNotes(json.optString("body")), it, size) }
        } finally {
            connection.disconnect()
        }
    }

    // ---- Download & install ---------------------------------------------------------------

    /** "Download & install" / "Install now". Downloads if needed, then opens Android's installer. */
    fun downloadAndInstall(activity: Activity) {
        val release = available ?: return
        when (status) {
            is Status.Downloading, Status.Installing -> return
            else -> Unit
        }
        val file = apkFile(activity, release)
        if (file.isFile) {
            install(activity)
            return
        }
        val app = activity.applicationContext
        status = Status.Downloading(if (release.sizeBytes > 0) 0f else null)
        downloadJob = scope.launch {
            val result = runCatching { download(app, release, file) }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    status = Status.Ready
                    // Still on screen: go straight to the installer. Otherwise "Install now" waits in the banner.
                    if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) install(activity)
                }
                result.onFailure { error ->
                    file.delete()
                    if (error is kotlinx.coroutines.CancellationException) {
                        status = Status.Idle
                    } else {
                        status = Status.Failed(error.message?.takeIf { it.length < 240 } ?: "The download didn't finish. Check the TV's internet and try again.")
                    }
                }
            }
        }
    }

    private suspend fun download(context: Context, release: Release, target: File) {
        val dir = updatesDir(context)
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        val part = File(dir, target.name + ".part")
        val connection = openFollowingRedirects(URL(release.apkUrl))
        try {
            if (connection.responseCode !in 200..299) error("The download didn't start (GitHub answered ${connection.responseCode}). Try again in a bit.")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes.takeIf { it > 0 }
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null) {
                            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != shown) {
                                shown = percent
                                withContext(Dispatchers.Main) { status = Status.Downloading(percent / 100f) }
                            }
                        }
                    }
                }
            }
            if (total != null && done < total) error("The download was cut off. Try again.")
        } finally {
            connection.disconnect()
        }
        checkDownloadedApk(context, part, release)
        if (!part.renameTo(target)) error("Couldn't save the update. Try again.")
    }

    /** GitHub sends downloads on to its file server; follow those hops ourselves to be safe. */
    private fun openFollowingRedirects(start: URL): HttpURLConnection {
        var url = start
        repeat(6) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NuvioLaunch-updater")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection.responseCode
            val next = connection.getHeaderField("Location")
            if (code !in 300..399 || next == null) return connection
            connection.disconnect()
            url = URL(url, next)
        }
        error("The download kept bouncing between servers. Try again in a bit.")
    }

    /** Makes sure the file really is a newer Nuvio Launch, signed the same way, before Android sees it. */
    private fun checkDownloadedApk(context: Context, file: File, release: Release) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("The downloaded file isn't a working app. Try again.")
        if (archive.packageName != context.packageName) error("The download isn't Nuvio Launch. Nothing was installed.")
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        val newSigners = signers(archive)
        val oldSigners = installed?.let { signers(it) }
        // Only refuse when both are known and clearly different; Android itself checks again.
        if (!newSigners.isNullOrEmpty() && !oldSigners.isNullOrEmpty() && newSigners != oldSigners) {
            error("This update is signed differently from the app on your TV, so Android won't install it over it. Uninstall Nuvio Launch once, then install it from GitHub.")
        }
        if (!isNewer(archive.versionName.orEmpty(), installedVersion(context))) {
            error("The download isn't newer than what you have.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String>? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        return raw?.map { it.toCharsString() }?.toSet()
    }

    /** Opens Android's installer for the downloaded update, asking for the install permission first if needed. */
    fun install(activity: Activity) {
        val release = available ?: return
        val file = apkFile(activity, release)
        if (!file.isFile) {
            status = Status.Idle
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            status = Status.NeedsPermission
            openInstallPermission(activity)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        val fallback = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { activity.startActivity(view); true }.getOrDefault(false) ||
            runCatching { activity.startActivity(fallback); true }.getOrDefault(false)
        if (opened) {
            installerOpenedAt = SystemClock.elapsedRealtime()
            status = Status.Installing
        } else {
            status = Status.Failed("This TV didn't open its app installer. You can install the update from $RELEASES_PAGE.")
        }
    }

    /** Android's "Install unknown apps" switch for Nuvio Launch. */
    fun openInstallPermission(activity: Activity) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
        intents.firstOrNull { intent -> runCatching { activity.startActivity(intent); true }.getOrDefault(false) }
    }

    /**
     * Call from onResume of every screen that shows the banner. Continues after the user turns
     * on the install permission, and brings "Install now" back if they closed the installer.
     */
    fun onResume(activity: Activity) {
        if (!supported) return
        when (status) {
            Status.NeedsPermission -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) install(activity)
            }
            Status.Installing -> {
                // Back here with the app not replaced: the install prompt was closed. Offer it again.
                if (SystemClock.elapsedRealtime() - installerOpenedAt > 1_000) status = if (available?.let { apkFile(activity, it).isFile } == true) Status.Ready else Status.Idle
            }
            else -> Unit
        }
        maybeCheck(activity)
    }

    fun dismissError() {
        if (status is Status.Failed) status = Status.Idle
    }

    private fun clearDownloads(context: Context) {
        updatesDir(context).listFiles()?.forEach { it.delete() }
    }

    // ---- Versions & notes -----------------------------------------------------------------

    private fun parseVersion(text: String): List<Int>? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(text) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    /** True when [candidate] (e.g. "0.3.0") is higher than [current] (e.g. "0.2.0"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return true
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Turns GitHub's release notes (Markdown) into plain lines for the TV. */
    private fun cleanNotes(markdown: String): String =
        markdown.lineSequence()
            .map { it.trimEnd() }
            .map { line ->
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("#") -> trimmed.trimStart('#').trim()
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "• " + trimmed.drop(2).trim()
                    else -> trimmed
                }
            }
            .map { it.replace("**", "").replace("__", "").replace("`", "").replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
