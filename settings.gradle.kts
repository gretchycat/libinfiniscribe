pluginManagement {
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "org.jetbrains.kotlin.jvm") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
        if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:8.9.2")
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.omicronapps") } }
    }
}
// Source dependencies are siblings pinned by the containing workspace.
val familyRoot = providers.gradleProperty("infiniLibrariesDir").orNull?.let(::file) ?: file("..")
rootProject.name = "libinfiniscribe"
check(familyRoot.resolve("libinfinistream/settings.gradle.kts").isFile) { "Missing sibling libinfinistream; clone workspace dependencies or set -PinfiniLibrariesDir" }
includeBuild(familyRoot.resolve("libinfinistream"))
include(":scribe-core", ":scribe-reparse-api", ":scribe-reparse-yaml", ":scribe-reparse-xml", ":scribe-reparse-toml")
if (System.getenv("ANDROID_HOME").isNullOrBlank() && System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() && System.getProperty("android.home").isNullOrBlank()) {
    val props = java.util.Properties()
    val local = file("local.properties")
    if (local.isFile) local.inputStream().use { props.load(it) }
    props.getProperty("sdk.dir")?.let { System.setProperty("android.home", file(it).absolutePath) }
}
// The portable core remains buildable on machines without an Android SDK.
if (!System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
    !System.getProperty("android.home").isNullOrBlank()) include(":scribe-android", ":scribe-reparse-json-android")
