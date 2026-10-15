package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudePaths
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile

/** What a command printed and returned. */
class CliResult(val exitCode: Int, val stdoutBytes: ByteArray, val stderrBytes: ByteArray) {

    val out: String get() = String(stdoutBytes, StandardCharsets.UTF_8)
    val err: String get() = String(stderrBytes, StandardCharsets.UTF_8)

    /**
     * Standard output as the one JSON document of `--json` mode: it must be a single line, parse in
     * strict mode and have nothing after it.
     */
    fun json(): JsonObject {
        assertEquals("exactly one line on stdout: <$out>", 1, out.trimEnd().lines().size)
        val reader = JsonReader(StringReader(out))
        reader.strictness = Strictness.STRICT
        val document = JsonParser.parseReader(reader)
        assertEquals("nothing after the document", JsonToken.END_DOCUMENT, reader.peek())
        return document.asJsonObject
    }

    fun data(): JsonObject = json().getAsJsonObject("data")
}

/**
 * What the in-process tests of the CLI share: temporary homes of made-up accounts with sessions in them,
 * and a way to run a command line against them and read back what it printed.
 *
 * Every run is given its own standard streams, a fixed clock and a user home below the test folder, so
 * nothing of the real machine (its Claude home, its log folder, its environment) is touched.
 */
abstract class CliTest {

    @get:Rule
    val tmp = TemporaryFolder()

    protected val root: Path get() = tmp.root.toPath().toRealPath()

    /** The "system temp folder" of the runs: below the test folder, so that a leftover of a run can be looked for. */
    protected val systemTemp: Path get() = Files.createDirectories(root.resolve("system-temp"))

    /** The "now" of every run: Tuesday 12 March 2024, 08:30:15 UTC. */
    protected val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-12T08:30:15Z"), ZoneOffset.UTC)

    /** The user home the CLI believes it runs in. */
    protected val runnerHome: Path get() = root.resolve("runner")

    protected val logDir: Path get() = root.resolve("logs")

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private val machineLanguage: java.util.Locale = java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY)

    /** The texts the tests look for are the English ones, whatever language the machine that runs them speaks. */
    @Before
    fun speakEnglish() = java.util.Locale.setDefault(java.util.Locale.Category.DISPLAY, java.util.Locale.ENGLISH)

    @After
    fun restoreLanguage() = java.util.Locale.setDefault(java.util.Locale.Category.DISPLAY, machineLanguage)

    protected fun run(
        vararg args: String,
        stdin: String = "",
        windows: Boolean = false,
        environment: Map<String, String> = emptyMap(),
        interactiveInput: Boolean = false,
    ): CliResult = run(*args, windows = windows, environment = environment::get, stdin = stdin, interactiveInput = interactiveInput)

    /** The same, with the environment variables given as a function, so that a test can make reading one fail. */
    protected fun run(
        vararg args: String,
        windows: Boolean = false,
        environment: (String) -> String?,
        stdin: String = "",
        interactiveInput: Boolean = false,
    ): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val cliEnvironment = environmentOf(out, err, windows, environment, stdin, interactiveInput)
        val exitCode = SessionPorterCli(cliEnvironment).run(args.toList())
        cliEnvironment.out.flush()
        cliEnvironment.err.flush()
        return CliResult(exitCode, out.toByteArray(), err.toByteArray())
    }

    internal fun environmentOf(
        out: OutputStream,
        err: OutputStream,
        windows: Boolean = false,
        getenv: (String) -> String? = { null },
        stdin: String = "",
        interactiveInput: Boolean = false,
    ) = CliEnvironment(
        input = ByteArrayInputStream(stdin.toByteArray(StandardCharsets.UTF_8)),
        out = CliEnvironment.utf8(out),
        err = CliEnvironment.utf8(err),
        osName = if (windows) "Windows 11" else "Linux",
        userHome = runnerHome.toString(),
        getenv = getenv,
        clock = fixedClock,
        tempDirectory = systemTemp,
        inputIsInteractive = interactiveInput,
    )

    // ----------------------------------------------------------------------------------- fixtures --

    /** A Claude home that exists, below a folder named after the account. */
    protected fun claudeHomeOf(account: String): Path = Files.createDirectories(root.resolve(account).resolve(".claude"))

    private fun line(fields: Map<String, Any?>): String = gson.toJson(fields)

    private fun message(type: String, id: String, cwd: String, text: String, timestamp: String, extra: Map<String, Any?> = emptyMap()) = line(
        linkedMapOf<String, Any?>(
            "type" to type, "sessionId" to id, "cwd" to cwd, "timestamp" to timestamp,
            "message" to mapOf("role" to type, "content" to text),
        ) + extra,
    )

    /**
     * Writes a session of two messages recorded in [cwd] and returns its transcript. [title] is the first thing
     * the user said, which is what the session is called; [day] is the date of its messages in March 2024.
     */
    protected fun writeSession(
        home: Path,
        id: String,
        cwd: String,
        title: String = "Fix the build",
        day: Int = 10,
        extraLines: List<String> = emptyList(),
    ): Path {
        val projectDir = Files.createDirectories(home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd)))
        val stamp = "2024-03-%02dT09:00:0".format(day)
        val lines = listOf(
            message("user", id, cwd, title, stamp + "0.000Z"),
            message("assistant", id, cwd, "Done.", stamp + "5.000Z"),
        ) + extraLines
        val transcript = projectDir.resolve("$id.jsonl")
        Files.writeString(transcript, lines.joinToString("") { it + "\n" })
        return transcript
    }

    /** A line of a session that carries a git branch with the user name in it: a leftover no path replacement catches. */
    protected fun lineWithBareUserName(id: String, cwd: String, userName: String): String =
        message("user", id, cwd, "ok", "2024-03-10T09:00:09.000Z", mapOf("gitBranch" to "users/$userName/fix"))

    /** Writes a tool result under the auxiliary folder of a session. */
    protected fun writeAux(home: Path, cwd: String, id: String, name: String, content: String): Path {
        val dir = Files.createDirectories(home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd)).resolve(id).resolve("tool-results"))
        return Files.writeString(dir.resolve(name), content)
    }

    /** The ids of the sessions an archive holds. */
    protected fun sessionIdsIn(archive: Path): Set<String> = ZipFile(archive.toFile()).use { zip ->
        zip.entries().asSequence().map { it.name }.filter { it.endsWith("/transcript.jsonl") }.map { it.split('/')[1] }.toSet()
    }

    /** The transcripts of a home, whatever folder of `projects/` they are in. */
    protected fun transcriptsIn(home: Path): List<Path> {
        val projects = home.resolve("projects")
        if (!Files.isDirectory(projects)) return emptyList()
        return Files.walk(projects).use { paths -> paths.filter { it.toString().endsWith(".jsonl") && it.parent.parent == projects }.toList() }
    }

    /** A real export of [ids] of [home], through the CLI itself. */
    protected fun exportArchive(home: Path, vararg ids: String, name: String = "export.zip"): Path {
        val archive = root.resolve(name)
        val result = run("--home", home.toString(), "export", "--ids", ids.joinToString(","), "--out", archive.toString(), "--log-dir", logDir.toString(), "--json")
        assertEquals(result.out + result.err, 0, result.exitCode)
        return archive
    }

    // ------------------------------------------------------------------------------ crafted archives --

    /** The manifest of an archive of one session, as the export writes it (or, for format 1, as 1.0.x did). */
    protected fun manifestJson(
        id: String,
        formatVersion: Int = 2,
        folder: String = "{{SP:ROOT:enc}}",
        projectPath: String = "{{SP:ROOT:win}}",
    ): String {
        val session = """{"id":${gson.toJson(id)},"projectFolderName":${gson.toJson(folder)},"projectPath":${gson.toJson(projectPath)},""" +
            """"firstTimestamp":"2024-03-10T09:00:00.000Z","lastTimestamp":"2024-03-10T09:00:05.000Z","messageCount":2,"sizeBytes":1,"summary":null,"hasAuxData":false}"""
        val anonymization = if (formatVersion >= 2) {
            ""","anonymization":{"scheme":1,"tokenPrefix":"SP","kinds":["ROOT"],"sourceOs":"windows"}"""
        } else {
            ""
        }
        return """{"formatVersion":$formatVersion,"exportedAt":"x","producer":"Session Porter for Claude Code","sessions":[$session]$anonymization}"""
    }

    /** A ZIP with [manifest] as `manifest.json` (none when it is `null`) and the given entries. */
    protected fun writeZip(file: Path, manifest: String?, entries: Map<String, String> = emptyMap()): Path {
        java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            for ((name, content) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    /** A format 1 archive: no tokens, the paths of the machine that made it. */
    protected fun formatOneArchive(id: String, cwd: String = "C:\\Users\\alice\\work"): Path {
        val folder = ClaudePaths.encodeProjectPath(cwd)
        val transcript = gson.toJson(
            linkedMapOf<String, Any?>(
                "type" to "user", "sessionId" to id, "cwd" to cwd, "timestamp" to "2024-03-10T09:00:00.000Z",
                "message" to mapOf("role" to "user", "content" to "Old session"),
            ),
        ) + "\n"
        return writeZip(root.resolve("format1.zip"), manifestJson(id, formatVersion = 1, folder = folder, projectPath = cwd), mapOf("sessions/$id/transcript.jsonl" to transcript))
    }
}
