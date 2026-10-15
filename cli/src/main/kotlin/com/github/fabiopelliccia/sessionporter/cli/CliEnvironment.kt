package com.github.fabiopelliccia.sessionporter.cli

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
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
        )

        /**
         * A stream that always writes UTF-8, whatever the console or the default charset says: the JSON a
         * caller parses and the titles of the sessions must come out the same everywhere. It is flushed by
         * whoever owns it, once, when the command is over.
         */
        fun utf8(target: OutputStream): PrintStream = PrintStream(BufferedOutputStream(target), false, StandardCharsets.UTF_8)
    }
}
