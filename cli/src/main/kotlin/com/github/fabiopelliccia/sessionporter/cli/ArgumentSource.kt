package com.github.fabiopelliccia.sessionporter.cli

/**
 * Where the arguments of the command line come from.
 *
 * Normally they are the ones the JVM was started with. On Windows that is not safe for anything outside the
 * system's ANSI code page: `java.exe` receives its arguments through the ANSI API, so a folder called `Ωhome`
 * arrives as `?home`, and a `?` or `*` is then expanded as a wildcard against the files of the current folder.
 * Environment variables are Unicode on Windows, so a launcher can hand the arguments over in one of them instead:
 * [VARIABLE] holds every argument followed by a line feed, and is read only when the JVM got no arguments at all.
 * An argument that holds a line feed itself cannot be passed this way (none of the options takes one).
 */
internal object ArgumentSource {

    const val VARIABLE = "SESSION_PORTER_ARGS"

    fun resolve(arguments: List<String>, getenv: (String) -> String?): List<String> {
        if (arguments.isNotEmpty()) return arguments
        return getenv(VARIABLE)?.let(::unpack) ?: arguments
    }

    /** One argument per line; an empty value has none at all, while a single empty argument is one empty line. */
    fun unpack(packed: String): List<String> =
        if (packed.isEmpty()) emptyList() else packed.removeSuffix("\n").split("\n")

    /** The inverse of [unpack]: what a launcher would put in [VARIABLE]. */
    fun pack(arguments: List<String>): String = arguments.joinToString("") { it + "\n" }
}
