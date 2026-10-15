package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LogFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun names(): List<String> =
        Files.list(tmp.root.toPath()).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    private fun touch(vararg names: String) = names.forEach { Files.writeString(tmp.root.toPath().resolve(it), "x") }

    @Test
    fun `keeps only the most recent files of the prefix`() {
        touch(
            "import-20260101-000001.log", "import-20260101-000002.log", "import-20260101-000003.log",
            "import-20260101-000004.log",
        )
        LogFiles.rotate(tmp.root.toPath(), "import", 2)
        assertEquals(listOf("import-20260101-000003.log", "import-20260101-000004.log"), names())
    }

    @Test
    fun `leaves other prefixes and other files alone`() {
        touch(
            "import-20260101-000001.log", "import-20260101-000002.log", "export-20260101-000001.log",
            "export-20260101-000002.log", "notes.txt", "import-20260101-000003.txt",
        )
        LogFiles.rotate(tmp.root.toPath(), "import", 1)
        assertEquals(
            listOf("export-20260101-000001.log", "export-20260101-000002.log", "import-20260101-000002.log", "import-20260101-000003.txt", "notes.txt"),
            names(),
        )
    }

    @Test
    fun `never touches a file that only looks like a log of the prefix`() {
        // The folder may be shared with other things: 22 notes of a user named like our logs, and the newest real log.
        val notes = (1..22).map { "export-notes-%02d.log".format(it) }
        touch(*notes.toTypedArray(), "export-20260101-000001.log", "export-2026.log", "export-20260101-000001.log.bak")

        LogFiles.rotate(tmp.root.toPath(), "export", 1)

        assertEquals((notes + listOf("export-2026.log", "export-20260101-000001.log", "export-20260101-000001.log.bak")).sorted(), names())
    }

    @Test
    fun `of logs of the same second the one with the higher counter is the newer`() {
        touch(
            "export-20260101-000001.log", "export-20260101-000001-2.log", "export-20260101-000001-10.log",
            "export-20260101-000002.log",
        )
        LogFiles.rotate(tmp.root.toPath(), "export", 3)
        // The oldest is the plain name of the first second, then -2, then -10, then the next second.
        assertEquals(listOf("export-20260101-000001-10.log", "export-20260101-000001-2.log", "export-20260101-000002.log"), names())
    }

    @Test
    fun `does nothing under the limit and on a missing folder`() {
        touch("import-20260101-000001.log")
        LogFiles.rotate(tmp.root.toPath(), "import", 5)
        assertEquals(listOf("import-20260101-000001.log"), names())

        LogFiles.rotate(tmp.root.toPath().resolve("missing"), "import", 1)
        assertTrue(Files.exists(tmp.root.toPath()))
    }
}
