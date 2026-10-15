package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.MessageBundle

/**
 * The texts the CLI itself shows (labels, errors, warnings), in the same ten languages as the core's
 * own bundle. The language follows `--lang`, `SESSION_PORTER_LANG` or the operating system, see
 * [SessionPorterCli]. JSON keys and error codes are never translated.
 */
internal object CliBundle : MessageBundle("messages.SessionPorterCliBundle")
