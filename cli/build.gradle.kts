plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.github.fabiopelliccia"
version = "1.0.0"

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

    // The dependencies ship no license files of their own: the texts that go with them are written down here.
    from(rootProject.file("LICENSE")) {
        into("META-INF/licenses")
        rename { "session-porter-LICENSE.txt" }
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
