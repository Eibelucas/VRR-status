package de.luca.mitfahrt.updates

import org.json.JSONObject

data class UpdateInfo(val versionCode: Long, val versionName: String, val assetName: String,
    val sha256: String, val size: Long, val minSdk: Int) {
    val downloadUrl get() = "$REPOSITORY/releases/download/v$versionName/$assetName"
    companion object {
        const val REPOSITORY = "https://github.com/Eibelucas/VRR-status"
        const val MANIFEST_URL = "$REPOSITORY/releases/latest/download/update.json"
        const val MAX_SIZE = 150L * 1024 * 1024
        fun parse(json: JSONObject, applicationId: String): UpdateInfo {
            require(json.getString("applicationId") == applicationId) { "Das Update gehört zu einer anderen App." }
            val code = json.getLong("versionCode")
            val name = json.getString("versionName")
            val asset = json.getString("apkAssetName")
            val digest = json.getString("sha256").lowercase()
            val size = json.getLong("size")
            val minSdk = json.getInt("minSdk")
            require(code > 0 && name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Ungültige Update-Version." }
            require(asset.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")) && !asset.contains("..")) { "Ungültige APK-Datei." }
            require(asset == "Mitfahrt-$name.apk") { "Das Update ist nicht die passende Handy-Version." }
            require(digest.matches(Regex("[0-9a-f]{64}")) && size in 1..MAX_SIZE && minSdk >= 1) { "Ungültige Update-Daten." }
            return UpdateInfo(code, name, asset, digest, size, minSdk)
        }
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
