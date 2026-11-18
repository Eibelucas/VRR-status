plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

val mitfahrtVersion = providers.gradleProperty("mitfahrt.version").get()
val (year, month, release) = requireNotNull(
    Regex("""([0-9]{2})\.([1-9]|1[0-2])\.(0|[1-9][0-9]?)""").matchEntire(mitfahrtVersion)
) { "Mitfahrt-Version als JJ.M.R angeben, z. B. 26.11.0 (Release 0 bis 99)." }.destructured

// Keep the Android code increasing across monthly and yearly releases.
extra["mitfahrtVersionName"] = mitfahrtVersion
extra["mitfahrtVersionCode"] = (2000 + year.toInt()) * 10_000 + month.toInt() * 100 + release.toInt()
