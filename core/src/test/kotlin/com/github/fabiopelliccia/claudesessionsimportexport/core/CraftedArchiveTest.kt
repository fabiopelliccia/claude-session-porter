package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.JsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile

/**
 * What the tests that import archives of their own making share: a Claude home that imports, the
 * manifest and the entries of a hand-made archive, and a real export of one session that a test can corrupt.
 */
abstract class CraftedArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    protected val root: Path get() = tmp.root.toPath()

    /** The Claude home of the machine that imports, and a project folder of it. */
    protected val victimHome: Path by lazy { Files.createDirectories(root.resolve("victim").resolve(".claude")) }
    protected val victimUserHome: Path get() = root.resolve("victim")
    protected val victimProject: String by lazy { Files.createDirectories(root.resolve("victim").resolve("work")).toString() }

    // ---------------------------------------------------------------------------- crafted archives --

    protected fun manifestJson(
        id: String,
        folder: String = "{{SP:ROOT:enc}}",
        formatVersion: Int = 2,
        anonymization: String? = """"anonymization":{"scheme":1,"tokenPrefix":"SP","kinds":["ROOT"],"sourceOs":"windows"}""",
    ): String {
        val session = """{"id":${jsonString(id)},"projectFolderName":${jsonString(folder)},"projectPath":"{{SP:ROOT:win}}",""" +
            """"firstTimestamp":null,"lastTimestamp":null,"messageCount":1,"sizeBytes":1,"summary":null,"hasAuxData":true}"""
        val parts = listOfNotNull(
            """"formatVersion":$formatVersion""",
            """"exportedAt":"x"""",
            """"producer":"Session Porter for Claude Code"""",
            """"sessions":[$session]""",
            anonymization,
        )
        return "{" + parts.joinToString(",") + "}"
    }

    protected fun jsonString(value: String): String = JsonPrimitive(value).toString()

    protected fun transcriptLine(id: String): String =
        """{"type":"user","cwd":"{{SP:ROOT:win}}","sessionId":${jsonString(id)},"timestamp":"2024-03-10T09:00:00.000Z","message":{"role":"user","content":"hi"}}""" + "\n"

    protected fun writeArchive(file: Path, manifest: String, entries: Map<String, String>): Path {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    /** An archive with one session and the three kinds of entry a session has. */
    protected fun archiveOf(id: String, extra: Map<String, String> = emptyMap(), manifest: String = manifestJson(id)): Path =
        writeArchive(
            root.resolve("crafted.zip"),
            manifest,
            mapOf(
                "sessions/$id/transcript.jsonl" to transcriptLine(id),
                "sessions/$id/aux/payload.bat" to "echo pwned\n",
                "sessions/$id/file-history/h@v1" to "x\n",
            ) + extra,
        )

    /** Every file the test folder holds apart from the archive itself and the folders the test created. */
    protected fun filesWritten(vararg except: Path): List<Path> =
        Files.walk(root).use { paths -> paths.filter { it.isRegularFile() && except.none { skip -> it == skip } }.toList() }

    protected fun importCrafted(archive: Path, policy: ConflictPolicy = ConflictPolicy.DUPLICATE): ImportOutcome =
        SessionArchive.import(
            archive, conflictPolicy = policy, targetProjectPath = victimProject, home = victimHome, userHome = victimUserHome.toString(),
        )

    /** An archive made by a real export of one session with a transcript, an auxiliary file and a backup. */
    protected fun exportedSession(id: String, auxBody: String = "tool result of the new session\n".repeat(200)): Path {
        val aliceHome = root.resolve("alice").resolve(".claude")
        val projectDir = Files.createDirectories(aliceHome.resolve("projects").resolve("C--Users-alice-proj"))
        Files.writeString(projectDir.resolve("$id.jsonl"), transcriptOf(id, "C:\\Users\\alice\\proj"))
        val aux = Files.createDirectories(projectDir.resolve(id).resolve("tool-results"))
        Files.writeString(aux.resolve("one.txt"), auxBody)
        val backups = Files.createDirectories(aliceHome.resolve("file-history").resolve(id))
        Files.writeString(backups.resolve("main@v1"), "backup of the new session\n".repeat(200))
        val archive = root.resolve("exported-$id.zip")
        val session = SessionScanner.listSessions(aliceHome).single { it.id == id }
        SessionArchive.export(listOf(session), archive, home = aliceHome, identity = SourceIdentity("C:\\Users\\alice"))
        return archive
    }

    protected fun transcriptOf(id: String, cwd: String): String =
        """{"type":"user","cwd":${jsonString(cwd)},"sessionId":"$id","timestamp":"2024-03-10T09:00:00.000Z","message":{"role":"user","content":"hi"}}""" + "\n"

    /** Flips some bytes in the middle of the compressed data of [entryName], so reading that entry fails. */
    protected fun corruptEntry(archive: Path, entryName: String): Path {
        val bytes = Files.readAllBytes(archive)
        val nameBytes = entryName.toByteArray(Charsets.UTF_8)
        var nameAt = -1
        for (start in 30..bytes.size - nameBytes.size) {
            if (bytes[start - 30] == 'P'.code.toByte() && bytes[start - 29] == 'K'.code.toByte() && bytes[start - 28] == 3.toByte() &&
                nameBytes.indices.all { bytes[start + it] == nameBytes[it] }
            ) {
                nameAt = start
                break
            }
        }
        check(nameAt >= 0) { "$entryName is not in the archive" }
        val extraLength = (bytes[nameAt - 2].toInt() and 0xFF) or ((bytes[nameAt - 1].toInt() and 0xFF) shl 8)
        val dataStart = nameAt + nameBytes.size + extraLength
        for (offset in dataStart + 4 until dataStart + 24) bytes[offset] = (bytes[offset].toInt() xor 0x5A).toByte()
        return Files.write(root.resolve("corrupt.zip"), bytes)
    }

    protected fun treeOf(dir: Path): Map<String, String> = Files.walk(dir).use { paths ->
        paths.filter { it.isRegularFile() }.toList().associate { dir.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
    }
}
