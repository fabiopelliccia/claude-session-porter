package com.github.fabiopelliccia.sessionporter.cli

import java.util.Properties

/** The version of the CLI, written into `session-porter-cli.properties` by the build (see `cli/build.gradle.kts`). */
internal object CliVersion {

    val value: String by lazy {
        val properties = Properties()
        CliVersion::class.java.getResourceAsStream("/session-porter-cli.properties")?.use { properties.load(it) }
        properties.getProperty("version")?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "unknown"
    }

    /** What the CLI tells the core it is, so the archive manifest and the import log say which tool wrote them. */
    const val COMPONENT = "cli"

    /** The name the import log records as the product that ran the import. */
    const val PRODUCT_NAME = "Session Porter CLI"
}
