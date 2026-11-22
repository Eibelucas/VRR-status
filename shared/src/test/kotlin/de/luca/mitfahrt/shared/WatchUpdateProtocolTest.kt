package de.luca.mitfahrt.shared

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WatchUpdateProtocolTest {
    private val digest = "ab".repeat(32)
    private val header = WatchApkHeader(20_261_200L, "26.12.0", 4_096L, digest)
    private val signers = setOf("synthetic-signer")

    @Test fun `watch build info roundtrips`() {
        val info = WatchBuildInfo(20_261_100L, "26.11.0", 34, canRequestPackageInstalls = false, reportedAtMillis = 1_700_000_000_000L)
        assertEquals(info, WatchUpdateProtocol.decodeInfo(WatchUpdateProtocol.encodeInfo(info)))
    }

    @Test fun `newer info revisions with trailing fields still decode`() {
        val info = WatchBuildInfo(5, "0.5.0", 30, true, 0)
        assertEquals(info, WatchUpdateProtocol.decodeInfo(WatchUpdateProtocol.encodeInfo(info) + byteArrayOf(1, 2, 3)))
    }

    @Test fun `malformed or oversized info is ignored`() {
        assertNull(WatchUpdateProtocol.decodeInfo(byteArrayOf()))
        assertNull(WatchUpdateProtocol.decodeInfo(ByteArray(WatchUpdateProtocol.MAX_INFO_BYTES + 1)))
        assertNull(WatchUpdateProtocol.decodeInfo("hello".toByteArray()))
        val valid = WatchUpdateProtocol.encodeInfo(WatchBuildInfo(5, "0.5.0", 30, true, 0))
        assertNull(WatchUpdateProtocol.decodeInfo(valid.copyOf(valid.size - 3)))
    }

    @Test fun `every status phase roundtrips and long messages are capped`() {
        for (phase in WatchApkPhase.entries) {
            val status = WatchApkStatus(phase, 7, "Meldung ${phase.name}")
            assertEquals(status, WatchUpdateProtocol.decodeStatus(WatchUpdateProtocol.encodeStatus(status)))
        }
        val long = WatchUpdateProtocol.decodeStatus(WatchUpdateProtocol.encodeStatus(WatchApkStatus(WatchApkPhase.ERROR, 1, "x".repeat(900))))!!
        assertEquals(WatchUpdateProtocol.MAX_MESSAGE_CHARS, long.message.length)
    }

    @Test fun `unknown status phase is ignored instead of misread`() {
        val bytes = WatchUpdateProtocol.encodeStatus(WatchApkStatus(WatchApkPhase.ERROR, 1, "a"))
        val text = String(bytes, Charsets.ISO_8859_1).replace("ERROR", "OTHER")
        assertNull(WatchUpdateProtocol.decodeStatus(text.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test fun `info, status and other watch payloads are not interchangeable`() {
        val info = WatchUpdateProtocol.encodeInfo(WatchBuildInfo(5, "0.5.0", 30, true, 0))
        val status = WatchUpdateProtocol.encodeStatus(WatchApkStatus(WatchApkPhase.INSTALLER_OPENED, 5, ""))
        assertNull(WatchUpdateProtocol.decodeStatus(info))
        assertNull(WatchUpdateProtocol.decodeInfo(status))
        val paths = setOf(WatchUpdateProtocol.WATCH_INFO_PATH, WatchUpdateProtocol.WATCH_INFO_REQUEST_PATH,
            WatchUpdateProtocol.WATCH_APK_CHANNEL_PATH, WatchUpdateProtocol.WATCH_APK_STATUS_PATH)
        assertEquals(4, paths.size)
        assertTrue(paths.none { it in setOf(WatchProtocol.PATH, WatchProtocol.REQUEST, WatchCompanion.LIBRARY,
            WatchCompanion.COMMAND, WatchCompanion.RESULT, ArrivalAlert.PATH, HapticTestProtocol.REQUEST, HapticTestProtocol.ACK) })
    }

    @Test fun `header is read from the stream and leaves the APK bytes untouched`() {
        val apk = ByteArray(16) { it.toByte() }
        val stream = ByteArrayInputStream(WatchUpdateProtocol.encodeHeader(header) + apk)
        assertEquals(header, WatchUpdateProtocol.readHeader(stream))
        assertArrayEquals(apk, stream.readBytes())
    }

    @Test(expected = IllegalArgumentException::class) fun `truncated header is rejected`() {
        val bytes = WatchUpdateProtocol.encodeHeader(header)
        WatchUpdateProtocol.readHeader(ByteArrayInputStream(bytes.copyOf(bytes.size - 4)))
    }

    @Test(expected = IllegalArgumentException::class) fun `foreign stream is rejected`() {
        WatchUpdateProtocol.readHeader(ByteArrayInputStream("PK\u0003\u0004 not a header".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class) fun `APK above the watch size cap is rejected`() {
        WatchUpdateProtocol.encodeHeader(header.copy(size = WatchUpdateProtocol.MAX_APK_BYTES + 1))
    }

    @Test(expected = IllegalArgumentException::class) fun `invalid digest is rejected`() {
        WatchUpdateProtocol.encodeHeader(header.copy(sha256 = "AB".repeat(32)))
    }

    @Test(expected = IllegalArgumentException::class) fun `path-like version name is rejected`() {
        WatchUpdateProtocol.encodeHeader(header.copy(versionName = "../1.2.3"))
    }

    private fun verify(received: Long = header.size, sha: String = digest, pkg: String? = "de.luca.mitfahrt",
        code: Long? = header.versionCode, installed: Long = 20_261_100L, archiveSigners: Set<String> = signers,
        installedSigners: Set<String> = signers) = WatchUpdateProtocol.verifyReceived(header, received, sha,
        "de.luca.mitfahrt", pkg, code, installed, installedSigners, archiveSigners)

    @Test fun `matching signed APK passes, including a reinstall of the same version`() {
        assertNull(verify())
        assertNull(verify(sha = digest.uppercase()))
        assertNull(verify(installed = header.versionCode))
    }

    @Test fun `every receiver check produces a German reason`() {
        assertNotNull(verify(received = header.size - 1))
        assertNotNull(verify(sha = "cd".repeat(32)))
        assertNotNull(verify(pkg = null))
        assertNotNull(verify(pkg = "another.app"))
        assertNotNull(verify(code = header.versionCode + 1))
        assertNotNull(verify(installed = header.versionCode + 1))
        assertNotNull(verify(archiveSigners = setOf("other")))
        assertNotNull(verify(installedSigners = emptySet(), archiveSigners = emptySet()))
        assertFalse(verify(received = 1)!!.contains('—'))
    }
}
