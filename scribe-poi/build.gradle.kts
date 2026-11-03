import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.4.3"
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
val backend by configurations.creating { isCanBeConsumed = false }
dependencies {
    backend("org.apache.poi:poi-ooxml:5.5.1")
    backend("org.apache.poi:poi-scratchpad:5.5.1")
    backend("com.fasterxml:aalto-xml:1.3.3")
    backend("stax:stax-api:1.0.1")
    backend("org.apache.logging.log4j:log4j-api:2.26.1")
}
val notices = layout.buildDirectory.dir("generated/notices")
val collectNotices = tasks.register("collectNotices") {
    inputs.files(backend); outputs.dir(notices)
    doLast {
        val root = notices.get().asFile
        root.deleteRecursively()
        backend.files.forEach { jar -> ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory &&
                (it.name.substringAfterLast('/').uppercase().startsWith("LICENSE") ||
                 it.name.substringAfterLast('/').uppercase().startsWith("NOTICE")) }.forEach { entry ->
                val target = root.resolve("META-INF/libinfiniscribe-office/${jar.name}/${entry.name}")
                check(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
            }
        } }
    }
}
sourceSets.main { resources.srcDir(notices) }
tasks.processResources { dependsOn(collectNotices) }
val androidPoi = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(backend)
    archiveClassifier.set("android")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**", "module-info.class")
    exclude("META-INF/services/javax.xml.stream.*")
    relocate("javax.xml.namespace", "io.github.gretchycat.infiniscribe.poi.xml.namespace")
    relocate("javax.xml.stream", "io.github.gretchycat.infiniscribe.poi.xml.stream")
    relocate("javax.xml.XMLConstants", "io.github.gretchycat.infiniscribe.poi.xml.XMLConstants")
    relocate("java.awt.Color", "io.github.gretchycat.infiniscribe.poi.awt.SrgbColor")
    // The host already uses Commons libraries for storage/audio. Keep Office's
    // versions and their resource files private to this optional backend.
    relocate("org.apache.commons", "io.github.gretchycat.infiniscribe.poi.deps.commons")
    relocate("org.apache.logging", "io.github.gretchycat.infiniscribe.poi.deps.logging")
}
listOf("apiElements", "runtimeElements").forEach { name -> configurations.named(name) {
    outgoing.artifacts.clear()
    outgoing.artifact(androidPoi)
} }
// Explicit artifact avoids Java's classes-directory optimization bypassing shading.
configurations.create("androidJar") {
    isCanBeConsumed = true; isCanBeResolved = false
    outgoing.artifact(androidPoi)
}
