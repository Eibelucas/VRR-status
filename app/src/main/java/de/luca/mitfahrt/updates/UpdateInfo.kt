package de.luca.mitfahrt.updates

import de.luca.mitfahrt.shared.WatchUpdateProtocol
import org.json.JSONObject

data class UpdateInfo(val versionCode: Long, val versionName: String, val assetName: String,
    val sha256: String, val size: Long, val minSdk: Int) {
    val downloadUrl get() = "$REPOSITORY/releases/download/v$versionName/$assetName"
    companion object {
        const val REPOSITORY = "https://github.com/Eibelucas/VRR-status"
        const val MANIFEST_URL = "$REPOSITORY/releases/latest/download/update.json"
        /** Written next to update.json by `scripts/create_update_manifest.py --wear` in the release workflow. */
        const val WATCH_MANIFEST_URL = "$REPOSITORY/releases/latest/download/watch-update.json"
        const val MAX_SIZE = 150L * 1024 * 1024
        fun parse(json: JSONObject, applicationId: String): UpdateInfo =
            parse(json, applicationId, "Mitfahrt-", MAX_SIZE, "Das Update ist nicht die passende Handy-Version.")

        /** Same rules as [parse], but only the watch APK (`Mitfahrt-Wear-<version>.apk`) within the watch size cap. */
        fun parseWatch(json: JSONObject, applicationId: String): UpdateInfo =
            parse(json, applicationId, "Mitfahrt-Wear-", WatchUpdateProtocol.MAX_APK_BYTES, "Das ist keine Uhr-Version von Mitfahrt.")

        private fun parse(json: JSONObject, applicationId: String, prefix: String, maxSize: Long, wrongAsset: String): UpdateInfo {
            require(json.getString("applicationId") == applicationId) { "Das Update gehört zu einer anderen App." }
            val code = json.getLong("versionCode")
            val name = json.getString("versionName")
            val asset = json.getString("apkAssetName")
            val digest = json.getString("sha256").lowercase()
            val size = json.getLong("size")
            val minSdk = json.getInt("minSdk")
            require(code > 0 && name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Ungültige Update-Version." }
            require(asset.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")) && !asset.contains("..")) { "Ungültige APK-Datei." }
            require(asset == "$prefix$name.apk") { wrongAsset }
            require(digest.matches(Regex("[0-9a-f]{64}")) && size in 1..maxSize && minSdk >= 1) { "Ungültige Update-Daten." }
            return UpdateInfo(code, name, asset, digest, size, minSdk)
        }
    }
}

/** Copyable commands for a computer with ADB that is connected to the watch (wireless debugging). */
data class WatchAdbCommands(val allowInstalls: String, val install: String) {
    companion object {
        fun forApk(applicationId: String, apkName: String): WatchAdbCommands {
            require(applicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
            require(apkName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")))
            return WatchAdbCommands("adb shell appops set $applicationId REQUEST_INSTALL_PACKAGES allow", "adb install -r $apkName")
        }
    }
}

/**
 * The phone verifies a watch APK before it leaves the device. Phone and watch builds share one signing key, so the
 * installed phone app's signers are the reference. Reinstalling the same watch version is allowed; downgrades are not.
 */
fun verifyWatchApkIdentity(phone: ApkIdentity, downloaded: ApkIdentity, update: UpdateInfo, installedWatchVersionCode: Long?) {
    require(downloaded.applicationId == phone.applicationId) { "Die Uhr-APK gehört zu einer anderen App." }
    require(downloaded.versionCode == update.versionCode) { "Die Uhr-APK hat nicht die erwartete Version." }
    require(installedWatchVersionCode == null || downloaded.versionCode >= installedWatchVersionCode) {
        "Auf der Uhr ist bereits eine neuere Version installiert."
    }
    require(phone.signerDigests.isNotEmpty() && phone.signerDigests == downloaded.signerDigests) {
        "Die Uhr-APK ist nicht mit dem Schlüssel deiner installierten App signiert."
    }
}

data class ApkIdentity(val applicationId: String, val versionCode: Long, val signerDigests: Set<String>)

fun verifyUpdateIdentity(installed: ApkIdentity, downloaded: ApkIdentity, update: UpdateInfo) {
    require(downloaded.applicationId == installed.applicationId) { "Die APK gehört zu einer anderen App." }
    require(downloaded.versionCode == update.versionCode && downloaded.versionCode > installed.versionCode) {
        "Die APK hat nicht die erwartete neue Version."
    }
    require(installed.signerDigests.isNotEmpty() && installed.signerDigests == downloaded.signerDigests) {
        "Die APK ist nicht mit dem Schlüssel deiner installierten App signiert."
    }
}
