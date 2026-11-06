package de.luca.mitfahrt.updates

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateInfoTest {
    private val app = "de.luca.mitfahrt"
    private fun manifest() = JSONObject().apply {
        put("applicationId", app); put("versionCode", 5); put("versionName", "0.5.0")
        put("apkAssetName", "Mitfahrt-0.5.0.apk"); put("sha256", "a".repeat(64)); put("size", 2048); put("minSdk", 29)
    }
    private fun update() = UpdateInfo.parse(manifest(), app)
    private val installed = ApkIdentity("de.luca.mitfahrt", 4, setOf("trusted-certificate"))

    @Test fun `release URL always stays inside the designated GitHub repository`() {
        assertEquals("https://github.com/Eibelucas/VRR-status/releases/download/v0.5.0/Mitfahrt-0.5.0.apk", update().downloadUrl)
    }
    @Test(expected = IllegalArgumentException::class) fun `other app manifest is rejected`() {
        UpdateInfo.parse(manifest().put("applicationId", "another.app"), app)
    }
    @Test(expected = IllegalArgumentException::class) fun `path traversal in asset is rejected`() {
        UpdateInfo.parse(manifest().put("apkAssetName", "../malware.apk"), app)
    }
    @Test(expected = IllegalArgumentException::class) fun `external URL cannot be used as an asset`() {
        UpdateInfo.parse(manifest().put("apkAssetName", "https://other.example/update.apk"), app)
    }
    @Test(expected = IllegalArgumentException::class) fun `watch APK cannot be offered as a phone update`() {
        UpdateInfo.parse(manifest().put("apkAssetName", "Mitfahrt-Wear-0.5.0.apk"), app)
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid digest is rejected`() {
        UpdateInfo.parse(manifest().put("sha256", "anything"), app)
    }
    @Test(expected = IllegalArgumentException::class) fun `oversized download is rejected`() {
        UpdateInfo.parse(manifest().put("size", UpdateInfo.MAX_SIZE + 1), app)
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid tag version is rejected`() {
        UpdateInfo.parse(manifest().put("versionName", "../../../other"), app)
    }
    @Test fun `newer signed package is accepted`() {
        verifyUpdateIdentity(installed, installed.copy(versionCode = 5), update())
    }
    @Test(expected = IllegalArgumentException::class) fun `APK with different signature is rejected`() {
        verifyUpdateIdentity(installed, installed.copy(versionCode = 5, signerDigests = setOf("other")), update())
    }
    @Test(expected = IllegalArgumentException::class) fun `unsigned APK is rejected`() {
        verifyUpdateIdentity(installed, installed.copy(versionCode = 5, signerDigests = emptySet()), update())
    }
    @Test(expected = IllegalArgumentException::class) fun `different APK package is rejected`() {
        verifyUpdateIdentity(installed, installed.copy(applicationId = "another.app", versionCode = 5), update())
    }
    @Test(expected = IllegalArgumentException::class) fun `APK version must match manifest`() {
        verifyUpdateIdentity(installed, installed.copy(versionCode = 6), update())
    }
    @Test(expected = IllegalArgumentException::class) fun `same installed version is not an upgrade`() {
        verifyUpdateIdentity(installed, installed, update().copy(versionCode = 4))
    }
    @Test(expected = IllegalArgumentException::class) fun `downgrades are rejected`() {
        verifyUpdateIdentity(installed, installed.copy(versionCode = 3), update().copy(versionCode = 3))
    }
}
