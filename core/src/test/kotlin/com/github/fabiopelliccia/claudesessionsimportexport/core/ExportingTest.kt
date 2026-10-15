package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.GsonBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * What the tests of the export share: a Claude home of a made-up account to write sessions into, and the
 * entries of the archive read back as bytes and text.
 */
abstract class ExportingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    protected val root: Path get() = tmp.root.toPath()

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    /** A Claude home that does not exist yet, below a folder named after the account. */
    protected fun claudeHomeOf(account: String): Path = root.resolve(account).resolve(".claude")

    /** One line of a transcript, JSON-escaped the way Claude Code writes it. */
    protected fun jsonLine(fields: Map<String, Any?>): String = gson.toJson(fields)

    protected fun userLine(id: String, cwd: String, content: String, second: Int = 0): String = jsonLine(
        mapOf(
            "type" to "user", "sessionId" to id, "cwd" to cwd, "timestamp" to "2024-03-10T09:00:0$second.000Z",
            "message" to mapOf("role" to "user", "content" to content),
        ),
    )

    /** The folder of the sessions recorded in [cwd], and where the auxiliary folder of [id] is. */
    protected fun projectDirOf(home: Path, cwd: String): Path =
        Files.createDirectories(home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd)))

    protected fun auxDirOf(home: Path, cwd: String, id: String): Path =
        Files.createDirectories(projectDirOf(home, cwd).resolve(id).resolve("tool-results"))

    /** Writes a session of two messages (and any [extraLines]) and returns it as the export table lists it. */
    protected fun writeSession(home: Path, id: String, cwd: String, content: String = "hi", extraLines: List<String> = emptyList()): SessionInfo {
        val lines = listOf(userLine(id, cwd, content), userLine(id, cwd, "again", second = 5)) + extraLines
        Files.writeString(projectDirOf(home, cwd).resolve("$id.jsonl"), lines.joinToString("") { it + "\n" })
        return SessionScanner.listSessions(home).single { it.id == id }
    }

    /** Every entry of [archive] by name, decompressed. */
    protected fun entriesOf(archive: Path): Map<String, ByteArray> = ZipFile(archive.toFile()).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }
    }

    protected fun textOf(archive: Path, name: String): String = String(entriesOf(archive).getValue(name), Charsets.UTF_8)
}
