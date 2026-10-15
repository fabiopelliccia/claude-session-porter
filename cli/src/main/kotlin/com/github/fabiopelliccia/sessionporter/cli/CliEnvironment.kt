package com.github.fabiopelliccia.sessionporter.cli

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock

/**
 * Everything the CLI takes from the process it runs in: the three standard streams, the operating
 * system and a few environment values. The real process is [system]; the tests hand in their own so
 * that a whole command runs in-process and its output can be read back.
 */
internal class CliEnvironment(
    val input: InputStream,
    val out: PrintStream,
    val err: PrintStream,
    val osName: String,
    val userHome: String,
    val getenv: (String) -> String?,
    val clock: Clock = Clock.systemDefaultZone(),
    /** Where temporary files go: a folder that is not synced and is cleaned up by the system. */
    val tempDirectory: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    /** Whether [input] is a person typing at a console, which a command that reads all of it would wait for forever. */
    val inputIsInteractive: Boolean = false,
) {
    val isWindows: Boolean get() = osName.startsWith("Windows", ignoreCase = true)
    val isMac: Boolean get() = osName.startsWith("Mac", ignoreCase = true)

    companion object {

        fun system(): CliEnvironment = CliEnvironment(
            input = System.`in`,
            out = utf8(FileOutputStream(FileDescriptor.out)),
            err = utf8(FileOutputStream(FileDescriptor.err)),
            osName = System.getProperty("os.name"),
            userHome = System.getProperty("user.home"),
            getenv = System::getenv,
            // Before Java 22 the console exists only when both input and output are a terminal. With the output
            // redirected (which is how the plugin runs the CLI) it is missing, and so is this protection; a
            // redirected input is never taken for a console either way.
            inputIsInteractive = System.console() != null,
        )

        /**
         * A stream that always writes UTF-8, whatever the console or the default charset says: the JSON a
         * caller parses and the titles of the sessions must come out the same everywhere. It is flushed by
         * whoever owns it, once, when the command is over.
         */
        fun utf8(target: OutputStream): PrintStream = PrintStream(BufferedOutputStream(target), false, StandardCharsets.UTF_8)
    }
}
