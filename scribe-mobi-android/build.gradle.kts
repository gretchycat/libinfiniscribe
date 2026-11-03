plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.gretchycat.infiniscribe.mobi"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("mobiNative"))
    } else { externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } } }
}
if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    val native = tasks.register<Exec>("buildMobiNative") {
        inputs.dir("src/main/cpp"); outputs.dir(layout.buildDirectory.dir("mobiNative"))
        commandLine("sh", file("src/main/cpp/build-mobi.sh"), layout.buildDirectory.dir("mobiNative").get().asFile, System.getProperty("java.home"))
    }
    tasks.named("preBuild").configure { dependsOn(native) }
}
dependencies { api(project(":scribe-core")) }
