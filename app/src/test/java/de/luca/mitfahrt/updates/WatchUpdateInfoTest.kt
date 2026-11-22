package de.luca.mitfahrt.updates

import de.luca.mitfahrt.shared.WatchApkHeader
import de.luca.mitfahrt.shared.WatchApkPhase
import de.luca.mitfahrt.shared.WatchApkStatus
import de.luca.mitfahrt.shared.WatchBuildInfo
import de.luca.mitfahrt.shared.WatchUpdateProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WatchUpdateInfoTest {
    private val app = "de.luca.mitfahrt"
    /** Same shape `scripts/create_update_manifest.py --wear` writes to watch-update.json. */
    private fun manifest() = JSONObject().apply {
        put("applicationId", app); put("versionCode", 20_261_200); put("versionName", "26.12.0")
        put("minSdk", 30); put("apkAssetName", "Mitfahrt-Wear-26.12.0.apk"); put("size", 4096); put("sha256", "c".repeat(64))
    }
    private fun watchUpdate() = UpdateInfo.parseWatch(manifest(), app)
    private val phone = ApkIdentity(app, 20_261_100, setOf("synthetic-signer"))

    @Test fun `watch manifest parses and downloads from the same release`() {
        val info = watchUpdate()
        assertEquals("https://github.com/Eibelucas/VRR-status/releases/download/v26.12.0/Mitfahrt-Wear-26.12.0.apk", info.downloadUrl)
        assertEquals("https://github.com/Eibelucas/VRR-status/releases/latest/download/watch-update.json", UpdateInfo.WATCH_MANIFEST_URL)
        assertEquals(30, info.minSdk)
    }

    @Test(expected = IllegalArgumentException::class) fun `phone APK cannot be offered as a watch update`() {
        UpdateInfo.parseWatch(manifest().put("apkAssetName", "Mitfahrt-26.12.0.apk"), app)
    }

    @Test(expected = IllegalArgumentException::class) fun `watch asset must match the version name`() {
        UpdateInfo.parseWatch(manifest().put("apkAssetName", "Mitfahrt-Wear-26.11.0.apk"), app)
    }

    @Test(expected = IllegalArgumentException::class) fun `watch manifest of another app is rejected`() {
        UpdateInfo.parseWatch(manifest().put("applicationId", "another.app"), app)
    }

    @Test(expected = IllegalArgumentException::class) fun `watch APK above the transfer cap is rejected`() {
        UpdateInfo.parseWatch(manifest().put("size", WatchUpdateProtocol.MAX_APK_BYTES + 1), app)
    }

    @Test fun `phone parsing keeps rejecting the watch manifest`() {
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo.parse(manifest(), app) }
    }

    @Test fun `parsed watch update always yields a valid transfer header`() {
        val info = watchUpdate()
        val header = WatchApkHeader(info.versionCode, info.versionName, info.size, info.sha256)
        assertEquals(header, WatchUpdateProtocol.readHeader(ByteArrayInputStream(WatchUpdateProtocol.encodeHeader(header))))
    }

    @Test fun `phone-signed watch APK is accepted, including the same version as installed`() {
        verifyWatchApkIdentity(phone, phone.copy(versionCode = 20_261_200), watchUpdate(), null)
        verifyWatchApkIdentity(phone, phone.copy(versionCode = 20_261_200), watchUpdate(), 20_261_200)
    }

    @Test fun `watch APK checks reject foreign, mismatched and older files`() {
        val update = watchUpdate()
        val good = phone.copy(versionCode = update.versionCode)
        assertThrows(IllegalArgumentException::class.java) { verifyWatchApkIdentity(phone, good.copy(applicationId = "another.app"), update, null) }
        assertThrows(IllegalArgumentException::class.java) { verifyWatchApkIdentity(phone, good.copy(versionCode = 1), update, null) }
        assertThrows(IllegalArgumentException::class.java) { verifyWatchApkIdentity(phone, good, update, update.versionCode + 1) }
        assertThrows(IllegalArgumentException::class.java) { verifyWatchApkIdentity(phone, good.copy(signerDigests = setOf("other")), update, null) }
        assertThrows(IllegalArgumentException::class.java) {
            verifyWatchApkIdentity(phone.copy(signerDigests = emptySet()), good.copy(signerDigests = emptySet()), update, null)
        }
    }

    @Test fun `ADB commands name the package and the downloaded file`() {
        val commands = WatchAdbCommands.forApk(app, "Mitfahrt-Wear-26.12.0.apk")
        assertEquals("adb shell appops set de.luca.mitfahrt REQUEST_INSTALL_PACKAGES allow", commands.allowInstalls)
        assertEquals("adb install -r Mitfahrt-Wear-26.12.0.apk", commands.install)
        assertThrows(IllegalArgumentException::class.java) { WatchAdbCommands.forApk(app, "x.apk; rm -rf /") }
        assertThrows(IllegalArgumentException::class.java) { WatchAdbCommands.forApk("de.luca.mitfahrt && reboot", "a.apk") }
    }

    @Test fun `unreported watch version is never shown as current`() {
        val info = watchUpdate()
        assertEquals(WatchUpdatePhase.AVAILABLE, watchPhaseFor(info, null))
        assertEquals(WatchUpdatePhase.AVAILABLE, watchPhaseFor(info, WatchBuildInfo(20_261_100, "26.11.0", 34, true, 0)))
        assertEquals(WatchUpdatePhase.CURRENT, watchPhaseFor(info, WatchBuildInfo(20_261_200, "26.12.0", 34, true, 0)))
    }

    @Test fun `watch status maps only for the version that was sent`() {
        val sent = 20_261_200L
        fun status(phase: WatchApkPhase, code: Long = sent) = WatchApkStatus(phase, code, "")
        assertNull(watchPhaseFor(status(WatchApkPhase.INSTALLER_OPENED), null))
        assertNull(watchPhaseFor(status(WatchApkPhase.INSTALLER_OPENED, 1), sent))
        assertEquals(WatchUpdatePhase.SENDING, watchPhaseFor(status(WatchApkPhase.RECEIVING), sent))
        assertEquals(WatchUpdatePhase.SENDING, watchPhaseFor(status(WatchApkPhase.VERIFYING), sent))
        assertEquals(WatchUpdatePhase.WAITING_ON_WATCH, watchPhaseFor(status(WatchApkPhase.WAITING_FOR_APP), sent))
        assertEquals(WatchUpdatePhase.INSTALLER_OPENED, watchPhaseFor(status(WatchApkPhase.INSTALLER_OPENED), sent))
        assertEquals(WatchUpdatePhase.PERMISSION_REQUIRED, watchPhaseFor(status(WatchApkPhase.PERMISSION_REQUIRED), sent))
        assertEquals(WatchUpdatePhase.ERROR, watchPhaseFor(status(WatchApkPhase.ERROR), sent))
        // A busy watch rejects before it read the header, so its error carries no version.
        assertEquals(WatchUpdatePhase.ERROR, watchPhaseFor(status(WatchApkPhase.ERROR, 0), sent))
    }
}
