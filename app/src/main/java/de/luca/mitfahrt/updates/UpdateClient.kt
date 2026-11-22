package de.luca.mitfahrt.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.shared.WatchUpdateProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class NoPublishedUpdate : IOException()

private const val WATCH_APK_PREFIX = "Mitfahrt-Wear-"
private const val WATCH_PARTIAL_PREFIX = "watch-download"

class UpdateClient(private val context: Context) : java.io.Closeable {
    private val settings = AppSettingsStore(context)
    override fun close() { settings.close() }
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        UpdateInfo.parse(manifest(UpdateInfo.MANIFEST_URL), context.packageName).also {
            require(it.minSdk <= Build.VERSION.SDK_INT) { "Diese Version benötigt eine neuere Android-Version." }
        }
    }

    /** The watch's Android version is checked by the caller once the watch reported it. */
    suspend fun checkWatch(): UpdateInfo = withContext(Dispatchers.IO) {
        UpdateInfo.parseWatch(manifest(UpdateInfo.WATCH_MANIFEST_URL), context.packageName)
    }

    private suspend fun manifest(url: String): JSONObject {
        val connection = connection(url)
        try {
            if (connection.responseCode == 404) throw NoPublishedUpdate()
            check(connection.responseCode == 200) { "GitHub ist gerade nicht erreichbar. Bitte später erneut versuchen." }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 65_536) { "Die Update-Antwort ist ungültig." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    /**
     * Downloads the watch APK next to phone updates and checks size, SHA-256, package and the signing key of the
     * installed phone app before anything is sent to the watch or shared.
     */
    suspend fun downloadWatch(update: UpdateInfo, installedWatchVersionCode: Long?, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        requireDownloadNetwork()
        val folder = File(context.cacheDir, "updates")
        check(folder.isDirectory || folder.mkdirs()) { "Download konnte nicht gespeichert werden." }
        val file = File(folder, update.assetName)
        if (file.isFile && file.length() == update.size && file.sha256() == update.sha256) {
            verifyWatchApk(file, update, installedWatchVersionCode)
            onProgress(1f)
            return@withContext file
        }
        val partial = File(folder, "$WATCH_PARTIAL_PREFIX.part.apk")
        val connection = connection(update.downloadUrl)
        try {
            check(connection.responseCode == 200) { "Die Uhr-APK konnte nicht heruntergeladen werden." }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    requireDownloadNetwork()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= update.size && count <= WatchUpdateProtocol.MAX_APK_BYTES) { "Die Uhr-APK hat eine unerwartete Größe." }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    onProgress((count.toFloat() / update.size).coerceIn(0f, 1f))
                }
            } }
            require(count == update.size && digest.digest().toHex() == update.sha256) { "Der Download ist beschädigt. Bitte erneut herunterladen." }
            verifyWatchApk(partial, update, installedWatchVersionCode)
            file.delete()
            check(partial.renameTo(file)) { "Die Uhr-APK konnte nicht gespeichert werden." }
            folder.listFiles().orEmpty().filter { it != file && it.name.startsWith(WATCH_APK_PREFIX) }.forEach { it.delete() }
            file
        } finally { connection.disconnect(); partial.delete() }
    }

    suspend fun download(update: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        requireDownloadNetwork()
        val folder = File(context.cacheDir, "updates")
        check(folder.isDirectory || folder.mkdirs()) { "Download konnte nicht gespeichert werden." }
        val file = File(folder, "Mitfahrt-${update.versionCode}.apk")
        val partial = File(folder, "download.part.apk")
        val connection = connection(update.downloadUrl)
        try {
            check(connection.responseCode == 200) { "Das Update konnte nicht heruntergeladen werden." }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    requireDownloadNetwork()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= update.size && count <= UpdateInfo.MAX_SIZE) { "Die APK hat eine unerwartete Größe." }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    onProgress((count.toFloat() / update.size).coerceIn(0f, 1f))
                }
            } }
            require(count == update.size && digest.digest().toHex() == update.sha256) { "Der Download ist beschädigt. Bitte erneut herunterladen." }
            verifyApk(partial, update)
            file.delete()
            check(partial.renameTo(file)) { "Die APK konnte nicht gespeichert werden." }
            folder.listFiles().orEmpty().filter { it != file && !it.name.startsWith(WATCH_APK_PREFIX) &&
                !it.name.startsWith(WATCH_PARTIAL_PREFIX) }.forEach { it.delete() }
            file
        } finally { connection.disconnect(); partial.delete() }
    }

    /** Human-readable release notes are optional; APK identity and checksum checks remain mandatory. */
    suspend fun changes(update: UpdateInfo): String? = withContext(Dispatchers.IO) {
        if (!settings.current().bool("update_show_changes", true)) return@withContext null
        val connection = connection("https://api.github.com/repos/Eibelucas/VRR-status/releases/tags/v${update.versionName}")
        try {
            if (connection.responseCode != 200) return@withContext null
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > 131_072) return@withContext null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            JSONObject(bytes.toString(Charsets.UTF_8)).optString("body").trim().take(12_000).ifBlank { null }
        } finally { connection.disconnect() }
    }

    private fun requireDownloadNetwork() {
        if (!settings.current().bool("update_wifi_only", false)) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        check(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Updates werden nur über WLAN geladen. Verbinde dich mit WLAN oder ändere die Update-Einstellung."
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, update: UpdateInfo) {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val downloaded = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Die heruntergeladene Datei ist keine gültige Android-App.")
        fun identity(info: PackageInfo) = ApkIdentity(info.packageName, info.longVersionCode,
            info.signingInfo?.apkContentsSigners.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
            }.toSet())
        verifyUpdateIdentity(identity(installed), identity(downloaded), update)
    }

    @Suppress("DEPRECATION")
    private fun verifyWatchApk(file: File, update: UpdateInfo, installedWatchVersionCode: Long?) {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val downloaded = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error(if (Build.VERSION.SDK_INT < update.minSdk) "Dieses Handy kann die Uhr-APK nicht prüfen, dafür ist Android ${androidRelease(update.minSdk)} nötig."
                else "Die heruntergeladene Datei ist keine gültige Android-App.")
        fun identity(info: PackageInfo) = ApkIdentity(info.packageName, info.longVersionCode,
            info.signingInfo?.apkContentsSigners.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
            }.toSet())
        verifyWatchApkIdentity(identity(installed), identity(downloaded), update, installedWatchVersionCode)
    }

    private fun androidRelease(sdk: Int) = when (sdk) { 30 -> "11"; 31, 32 -> "12"; 33 -> "13"; 34 -> "14"; 35 -> "15"; else -> "API $sdk" }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().toHex()
    }

    private fun connection(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "Mitfahrt-Android/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", "application/octet-stream, application/json")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
