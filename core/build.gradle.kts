plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    `java-library`
    `maven-publish`
}

group = "com.github.fabiopelliccia"
version = "2.0.0"

kotlin {
    jvmToolchain(21)

    compilerOptions {
        // Keep the API level aligned with the Kotlin stdlib bundled in the IDE.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
    }
}

dependencies {
    // Supplied by the consumer: the IDE bundles its own stdlib, the CLI adds it explicitly.
    compileOnly(kotlin("stdlib"))

    // Gson's only dependency, Error Prone annotations, is needed to compile Gson itself and never at
    // runtime: leaving it out keeps the distribution down to what is actually loaded.
    api("com.google.code.gson:gson:2.11.0") {
        exclude(group = "com.google.errorprone")
    }

    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

publishing {
    publications {
        create<MavenPublication>("core") {
            artifactId = "session-porter-core"
            from(components["java"])
        }
    }
}
