package de.luca.mitfahrt.wear

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.luca.mitfahrt.shared.WatchApkPhase
import de.luca.mitfahrt.shared.WatchApkStatus
import de.luca.mitfahrt.shared.WatchBuildInfo
import de.luca.mitfahrt.shared.WatchUpdateProtocol
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Tells the phone which watch build is installed and whether it may open the Android installer. */
object WatchVersionReporter {
    fun current(context: Context): WatchBuildInfo = WatchBuildInfo(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME,
        Build.VERSION.SDK_INT, context.packageManager.canRequestPackageInstalls(), System.currentTimeMillis())

    /** Blocking; call from a worker thread. Publishes a DataItem and, if given, answers the asking phone directly. */
    fun report(context: Context, replyTo: String? = null) {
        val app = context.applicationContext
        val bytes = WatchUpdateProtocol.encodeInfo(current(app))
        runCatching {
            val request = PutDataRequest.create(WatchUpdateProtocol.WATCH_INFO_PATH).setData(bytes).setUrgent()
            Tasks.await(Wearable.getDataClient(app).putDataItem(request), 10, TimeUnit.SECONDS)
        }
        val nodes = replyTo?.let(::listOf) ?: runCatching {
            Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS).map { it.id }
        }.getOrDefault(emptyList())
        for (node in nodes) runCatching {
            Tasks.await(Wearable.getMessageClient(app).sendMessage(node, WatchUpdateProtocol.WATCH_INFO_PATH, bytes), 10, TimeUnit.SECONDS)
        }
    }
}

/** After an update installs, the phone learns the new version without the watch app being opened. */
class WatchPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try { WatchUpdateReceiver.clearInstalled(context); WatchVersionReporter.report(context) } finally { pending.finish() }
        }.start()
    }
}

/**
 * Receives a phone-verified watch APK over the Wear OS channel, verifies it again on the watch and opens the
 * system installer. Android only lets apps start the installer while they are visible, so a transfer that
 * arrives in the background waits until Mitfahrt is opened on the watch.
 */
object WatchUpdateReceiver {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val FILE_NAME = "Mitfahrt-Wear.apk"
    private const val FREE_SPACE_MARGIN = 20L * 1024 * 1024
    private val busy = AtomicBoolean(false)
    @Volatile var foreground = false

    /** Blocking; WearableListenerService delivers channel events on a worker thread. */
    fun receive(context: Context, channel: ChannelClient.Channel) {
        val app = context.applicationContext
        val channels = Wearable.getChannelClient(app)
        val node = channel.nodeId
        if (!busy.compareAndSet(false, true)) {
            status(app, node, WatchApkPhase.ERROR, 0, "Auf der Uhr läuft bereits eine Übertragung.")
            runCatching { Tasks.await(channels.close(channel), 10, TimeUnit.SECONDS) }
            return
        }
        val folder = File(app.cacheDir, "updates")
        val partial = File(folder, "receive.part.apk")
        var versionCode = 0L
        try {
            check(folder.isDirectory || folder.mkdirs()) { "Speicher auf der Uhr nicht verfügbar." }
            Tasks.await(channels.getInputStream(channel), 30, TimeUnit.SECONDS).use { input ->
                val header = WatchUpdateProtocol.readHeader(input)
                versionCode = header.versionCode
                check(folder.usableSpace > header.size + FREE_SPACE_MARGIN) { "Nicht genug freier Speicher auf der Uhr." }
                status(app, node, WatchApkPhase.RECEIVING, versionCode, "Uhr empfängt Version ${header.versionName} …")
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (count < header.size) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), header.size - count).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                        count += read
                    }
                }
                status(app, node, WatchApkPhase.VERIFYING, versionCode, "Uhr prüft die APK …")
                verify(app, partial, header, count, digest.digest().toHex())?.let { throw IllegalArgumentException(it) }
                val target = File(folder, FILE_NAME)
                target.delete()
                check(partial.renameTo(target)) { "Die APK konnte auf der Uhr nicht gespeichert werden." }
                prefs(app).edit().putLong("pending_version", versionCode).putString("pending_node", node).apply()
                finish(app, node, target, versionCode)
            }
        } catch (error: IllegalArgumentException) {
            status(app, node, WatchApkPhase.ERROR, versionCode, error.message ?: "Die APK wurde abgelehnt.")
        } catch (error: IllegalStateException) {
            status(app, node, WatchApkPhase.ERROR, versionCode, error.message ?: "Die APK konnte nicht gespeichert werden.")
        } catch (_: Exception) {
            status(app, node, WatchApkPhase.ERROR, versionCode, "Die Übertragung wurde unterbrochen. Bitte erneut senden.")
        } finally {
            partial.delete()
            runCatching { Tasks.await(channels.close(channel), 10, TimeUnit.SECONDS) }
            busy.set(false)
        }
    }

    /** Called when the watch app becomes visible: starts a waiting, already verified install exactly once. */
    fun launchPending(context: Context) {
        val app = context.applicationContext
        if (busy.get()) return
        val prefs = prefs(app)
        val version = prefs.getLong("pending_version", 0L)
        val file = File(File(app.cacheDir, "updates"), FILE_NAME)
        if (version <= BuildConfig.VERSION_CODE || !file.isFile) {
            prefs.edit().remove("pending_version").remove("pending_node").apply()
            file.delete()
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) return
        val node = prefs.getString("pending_node", null)
        prefs.edit().remove("pending_version").remove("pending_node").apply()
        Thread {
            if (openInstaller(app, file)) node?.let { status(app, it, WatchApkPhase.INSTALLER_OPENED, version, "Installation auf der Uhr bestätigen.") }
            else node?.let { status(app, it, WatchApkPhase.ERROR, version, NO_INSTALLER) }
        }.start()
    }

    fun clearInstalled(context: Context) {
        val app = context.applicationContext
        val prefs = prefs(app)
        if (prefs.getLong("pending_version", 0L) > BuildConfig.VERSION_CODE) return
        prefs.edit().remove("pending_version").remove("pending_node").apply()
        File(File(app.cacheDir, "updates"), FILE_NAME).delete()
    }

    private fun finish(context: Context, node: String, file: File, versionCode: Long) {
        when {
            !context.packageManager.canRequestPackageInstalls() -> status(context, node, WatchApkPhase.PERMISSION_REQUIRED, versionCode,
                "APK geprüft und gespeichert. Die Uhr erlaubt Mitfahrt noch keine Installation. Gib das einmalig per ADB frei und öffne dann Mitfahrt auf der Uhr.")
            !foreground -> status(context, node, WatchApkPhase.WAITING_FOR_APP, versionCode,
                "APK geprüft und auf der Uhr gespeichert. Öffne Mitfahrt auf der Uhr, dann startet die Installation.")
            openInstaller(context, file) -> {
                prefs(context).edit().remove("pending_version").remove("pending_node").apply()
                status(context, node, WatchApkPhase.INSTALLER_OPENED, versionCode, "Installation auf der Uhr bestätigen.")
            }
            else -> status(context, node, WatchApkPhase.ERROR, versionCode, NO_INSTALLER)
        }
    }

    private const val NO_INSTALLER = "Die Uhr bietet keinen Installer an. Installiere die APK per ADB oder Wear Installer."

    @Suppress("DEPRECATION")
    private fun openInstaller(context: Context, file: File): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.watchupdates", file) }.getOrNull() ?: return false
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)) {
            try {
                context.startActivity(Intent(action).setDataAndType(uri, APK_MIME).addFlags(flags))
                return true
            } catch (_: ActivityNotFoundException) { /* Try the next installer action. */
            } catch (_: SecurityException) { return false }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, header: de.luca.mitfahrt.shared.WatchApkHeader, count: Long, sha256: String): String? {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = runCatching { pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        return WatchUpdateProtocol.verifyReceived(header, count, sha256, context.packageName, archive?.packageName,
            archive?.longVersionCode, installed.longVersionCode, signers(installed), archive?.let(::signers).orEmpty())
    }

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners.orEmpty()
        .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()

    private fun status(context: Context, node: String, phase: WatchApkPhase, versionCode: Long, message: String) {
        runCatching {
            Tasks.await(Wearable.getMessageClient(context).sendMessage(node, WatchUpdateProtocol.WATCH_APK_STATUS_PATH,
                WatchUpdateProtocol.encodeStatus(WatchApkStatus(phase, versionCode, message))), 10, TimeUnit.SECONDS)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("watch_update", Context.MODE_PRIVATE)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
