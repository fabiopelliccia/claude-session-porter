import java.io.FilterReader
import java.io.Reader
import java.io.StringReader

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.github.fabiopelliccia"
version = "1.0.0"

/**
 * Gives a text file the line endings `\n`. Without it the bytes of the jar depend on how the files were checked out:
 * a Windows checkout with `core.autocrlf` writes `\r\n` into the bundles and the licenses, and the same sources
 * would give another jar than on Linux. Used as a Gradle copy filter, which hands over the text of one file.
 */
class LineEndingsToLf(reader: Reader) : FilterReader(StringReader(reader.readText().replace("\r\n", "\n")))

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(kotlin("stdlib"))
    // Substituted by the included build in ../core.
    implementation("com.github.fabiopelliccia:session-porter-core:2.0.0")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass = "com.github.fabiopelliccia.sessionporter.cli.MainKt"
}

tasks.processResources {
    // The version the `version` command reports comes from here, so it is written in one place only.
    filesMatching("session-porter-cli.properties") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnit()
}

tasks.shadowJar {
    archiveFileName = "session-porter.jar"

    // Two builds of the same sources give the same bytes: no timestamps, entries in a fixed order.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    // Signatures of the dependencies' jars would no longer match the merged one, a module descriptor of a
    // single dependency is wrong for the fat jar, and the Kotlin module files only matter to a compiler.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    exclude("META-INF/*.kotlin_module")

    // The text files of the jar (the message bundles of the core and of the CLI, the licenses) get `\n` line endings
    // whatever the checkout used. They are read and written byte for byte as ISO-8859-1, so no character changes.
    filteringCharset = "ISO-8859-1"
    filesMatching(listOf("**/*.properties", "**/*.txt")) {
        filter(LineEndingsToLf::class.java)
    }

    // The dependencies ship no license files of their own: the texts that go with them are written down here.
    from(rootProject.file("LICENSE")) {
        into("META-INF/licenses")
        rename { "session-porter-LICENSE.txt" }
        filter(LineEndingsToLf::class.java)
    }
    from("src/main/licenses") {
        into("META-INF/licenses")
    }
}

// The launchers belong to the Claude Code plugin: the distributions the plugins add are not needed.
tasks.matching { it.name in setOf("distZip", "distTar", "shadowDistZip", "shadowDistTar") }.configureEach {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
