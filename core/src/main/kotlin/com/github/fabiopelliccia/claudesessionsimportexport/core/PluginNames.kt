package com.github.fabiopelliccia.claudesessionsimportexport.core

/**
 * The user facing name of the plugin, which is never translated. It is the `<name>` of
 * `plugin.xml`, the `pluginName` of `gradle.properties`, the id of the notification group, the title
 * of the message boxes and the `producer` written into every archive manifest: a test keeps them
 * aligned, because a notification group id that drifts from `plugin.xml` silently hides every
 * notification of the plugin.
 */
object PluginNames {
    const val DISPLAY_NAME = "Session Porter for Claude Code"
}
