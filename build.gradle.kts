import java.security.MessageDigest

// The root project builds nothing itself: it makes `./gradlew build` and `./gradlew test` build and test both parts,
// the core (an included build, whose tasks do not run on their own) and the CLI.
plugins {
    base
}

// The base plugin has no `test` task, so `./gradlew test` would only find the one of the CLI: this one brings the core's.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests of the core and of the CLI."
    dependsOn(gradle.includedBuild("core").task(":test"))
}

tasks.named("build") {
    dependsOn(gradle.includedBuild("core").task(":build"))
    dependsOn(":cli:build")
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("core").task(":check"))
}

// ---------------------------------------------------------------------------------- the plugin's jar --
// The Claude Code plugin ships the CLI jar in plugin/lib/, committed to the repository (a marketplace install is a
// plain git clone, so nothing is built on the user's computer). The jar is reproducible: the same sources give the
// same bytes on every system, which is what lets verifyPluginJar compare the committed copy with a fresh build.

val builtJar = project(":cli").layout.buildDirectory.file("libs/session-porter.jar")
val pluginJar = layout.projectDirectory.file("plugin/lib/session-porter.jar")
val pluginJarChecksum = layout.projectDirectory.file("plugin/lib/session-porter.jar.sha256")

/** The content of the checksum file: the format of `sha256sum`, `<hex>  <name>` and a line feed, whatever the system. */
fun checksumLine(jar: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
    return digest.joinToString("") { "%02x".format(it) } + "  " + jar.name + "\n"
}

tasks.register("syncPluginJar") {
    group = "distribution"
    description = "Builds the CLI jar and copies it, with its SHA-256, to plugin/lib/ for committing."
    dependsOn(":cli:shadowJar")
    inputs.file(builtJar)
    outputs.files(pluginJar, pluginJarChecksum)
    doLast {
        val source = builtJar.get().asFile
        pluginJar.asFile.parentFile.mkdirs()
        source.copyTo(pluginJar.asFile, overwrite = true)
        // Written as bytes: a text writer could turn the line feed into the line ending of the system.
        pluginJarChecksum.asFile.writeBytes(checksumLine(pluginJar.asFile).toByteArray(Charsets.US_ASCII))
        logger.lifecycle("plugin/lib/session-porter.jar updated: ${checksumLine(source).substringBefore(' ')}")
    }
}

// Not part of `build`: before a first `syncPluginJar` (or after any change to the sources) it fails by design.
// CI runs it explicitly, so a jar that does not match the sources never reaches the main branch.
tasks.register("verifyPluginJar") {
    group = "verification"
    description = "Fails when plugin/lib/session-porter.jar or its .sha256 differ from a fresh build of the sources."
    dependsOn(":cli:shadowJar")
    // Asked for together, the copy comes first.
    mustRunAfter("syncPluginJar")
    inputs.file(builtJar)
    inputs.files(pluginJar, pluginJarChecksum).optional()
    doLast {
        val fresh = builtJar.get().asFile
        val problems = mutableListOf<String>()
        if (!pluginJar.asFile.isFile) {
            problems += "plugin/lib/session-porter.jar does not exist."
        } else if (!pluginJar.asFile.readBytes().contentEquals(fresh.readBytes())) {
            problems += "plugin/lib/session-porter.jar is not the jar these sources build."
        }
        if (!pluginJarChecksum.asFile.isFile) {
            problems += "plugin/lib/session-porter.jar.sha256 does not exist."
        } else if (pluginJarChecksum.asFile.readText(Charsets.US_ASCII) != checksumLine(fresh)) {
            problems += "plugin/lib/session-porter.jar.sha256 does not match the jar these sources build."
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n") + "\nRun ./gradlew syncPluginJar and commit plugin/lib/.")
        }
    }
}
