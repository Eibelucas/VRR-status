package de.luca.mitfahrt.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream

/** What the watch reports about its installed build so the phone can offer the right APK. */
data class WatchBuildInfo(val versionCode: Long, val versionName: String, val sdkInt: Int,
    val canRequestPackageInstalls: Boolean, val reportedAtMillis: Long)

/** Sent once at the start of the APK channel, directly followed by exactly [size] APK bytes. */
data class WatchApkHeader(val versionCode: Long, val versionName: String, val size: Long, val sha256: String)

enum class WatchApkPhase { RECEIVING, VERIFYING, WAITING_FOR_APP, INSTALLER_OPENED, PERMISSION_REQUIRED, ERROR }

data class WatchApkStatus(val phase: WatchApkPhase, val versionCode: Long, val message: String)

/**
 * Phone to watch update transport. All paths are separate from ride, library and alert payloads, so watch
 * builds that do not know them simply ignore the messages. Decoders accept trailing bytes from newer
 * format revisions but never more than their size cap.
 */
object WatchUpdateProtocol {
    const val WATCH_INFO_PATH = "/mitfahrt/watch-info/v1"
    const val WATCH_INFO_REQUEST_PATH = "/mitfahrt/watch-info-request/v1"
    const val WATCH_APK_CHANNEL_PATH = "/mitfahrt/watch-apk/v1"
    const val WATCH_APK_STATUS_PATH = "/mitfahrt/watch-apk-status/v1"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val MAX_INFO_BYTES = 1_024
    const val MAX_STATUS_BYTES = 1_024
    const val MAX_HEADER_BYTES = 512
    const val MAX_MESSAGE_CHARS = 300
    private const val FORMAT = 1
    private const val HEADER_MAGIC = 0x4D465755 // "MFWU"
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun encodeInfo(info: WatchBuildInfo): ByteArray = write(MAX_INFO_BYTES) { out ->
        require(info.versionCode > 0 && info.sdkInt > 0)
        out.writeLong(info.versionCode); out.writeUTF(info.versionName.take(32)); out.writeInt(info.sdkInt)
        out.writeBoolean(info.canRequestPackageInstalls); out.writeLong(info.reportedAtMillis)
    }

    fun decodeInfo(bytes: ByteArray): WatchBuildInfo? = read(bytes, MAX_INFO_BYTES) { input ->
        val code = input.readLong(); val name = input.readUTF(); val sdk = input.readInt()
        val canInstall = input.readBoolean(); val reported = input.readLong()
        require(code > 0 && name.length in 1..32 && sdk in 1..1000 && reported >= 0)
        WatchBuildInfo(code, name, sdk, canInstall, reported)
    }

    fun encodeStatus(status: WatchApkStatus): ByteArray = write(MAX_STATUS_BYTES) { out ->
        out.writeUTF(status.phase.name); out.writeLong(status.versionCode); out.writeUTF(status.message.take(MAX_MESSAGE_CHARS))
    }

    fun decodeStatus(bytes: ByteArray): WatchApkStatus? = read(bytes, MAX_STATUS_BYTES) { input ->
        val phase = WatchApkPhase.valueOf(input.readUTF())
        val code = input.readLong(); val message = input.readUTF()
        require(code >= 0 && message.length <= MAX_MESSAGE_CHARS)
        WatchApkStatus(phase, code, message)
    }

    fun encodeHeader(header: WatchApkHeader): ByteArray {
        validate(header)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(HEADER_MAGIC); out.writeInt(FORMAT)
            out.writeLong(header.versionCode); out.writeUTF(header.versionName)
            out.writeLong(header.size); out.writeUTF(header.sha256)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_HEADER_BYTES) }
    }

    /** Reads only the header from the channel stream; the APK bytes stay unread in [input]. */
    fun readHeader(input: InputStream): WatchApkHeader {
        val data = DataInputStream(input)
        try {
            require(data.readInt() == HEADER_MAGIC) { "Unbekanntes Übertragungsformat." }
            require(data.readInt() == FORMAT) { "Diese Übertragung braucht eine neuere Uhr-App." }
            val code = data.readLong(); val name = data.readUTF(); val size = data.readLong(); val digest = data.readUTF()
            return WatchApkHeader(code, name, size, digest).also(::validate)
        } catch (_: EOFException) {
            throw IllegalArgumentException("Die Übertragung wurde unterbrochen.")
        }
    }

    /**
     * Pure checks the watch runs after receiving. Returns a short German error or null when the APK may be installed.
     * Signer sets contain SHA-256 digests of the signing certificates.
     */
    fun verifyReceived(header: WatchApkHeader, receivedBytes: Long, receivedSha256: String,
        expectedPackage: String, archivePackage: String?, archiveVersionCode: Long?,
        installedVersionCode: Long, installedSigners: Set<String>, archiveSigners: Set<String>): String? = when {
        receivedBytes != header.size -> "Die Übertragung ist unvollständig. Bitte erneut senden."
        receivedSha256.lowercase() != header.sha256 -> "Die APK ist beschädigt angekommen. Bitte erneut senden."
        archivePackage == null || archiveVersionCode == null -> "Die Datei ist keine gültige Android-App."
        archivePackage != expectedPackage -> "Die APK gehört zu einer anderen App."
        archiveVersionCode != header.versionCode -> "Die APK hat nicht die angekündigte Version."
        archiveVersionCode < installedVersionCode -> "Auf der Uhr ist bereits eine neuere Version installiert."
        installedSigners.isEmpty() || installedSigners != archiveSigners ->
            "Die APK ist nicht mit dem Schlüssel der installierten Uhr-App signiert."
        else -> null
    }

    private fun validate(header: WatchApkHeader) {
        require(header.versionCode > 0 && header.versionName.matches(versionPattern)) { "Ungültige Uhr-Version." }
        require(header.size in 1..MAX_APK_BYTES) { "Die APK ist zu groß für die Uhr." }
        require(header.sha256.matches(digestPattern)) { "Ungültige Prüfsumme." }
    }

    private fun write(max: Int, body: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out -> out.writeInt(FORMAT); body(out) }
    }.toByteArray().also { require(it.size <= max) }

    private fun <T> read(bytes: ByteArray, max: Int, body: (DataInputStream) -> T): T? = runCatching {
        require(bytes.size in 1..max)
        DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() >= FORMAT)
            body(it)
        }
    }.getOrNull()
}
