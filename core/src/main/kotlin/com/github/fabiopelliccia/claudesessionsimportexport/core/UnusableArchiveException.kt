package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.io.IOException

/**
 * An archive that cannot be imported, with the reason as a [Reason] that a front end can act on without reading the
 * message (which is written for a person, in the language of the machine). It is an [IOException], so a caller that
 * only wants to know that the archive is no good still catches it as before.
 */
class UnusableArchiveException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {

    enum class Reason {
        /** The file is not a ZIP at all. */
        NOT_A_ZIP,

        /** A ZIP without a `manifest.json`. */
        NO_MANIFEST,

        /** A `manifest.json` that cannot be read, or that lacks what its format needs. */
        UNREADABLE_MANIFEST,

        /** Made by another tool: it holds no Claude Code transcripts. */
        NOT_CLAUDE,

        /** Written in a format newer than this version understands. */
        TOO_NEW,

        /** Names that could write outside the Claude Code folders. */
        UNSAFE,
    }
}
