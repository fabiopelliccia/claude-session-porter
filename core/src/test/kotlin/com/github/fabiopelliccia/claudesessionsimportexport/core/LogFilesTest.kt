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
        touch("import-1.log", "import-2.log", "export-1.log", "export-2.log", "notes.txt", "import-3.txt")
        LogFiles.rotate(tmp.root.toPath(), "import", 1)
        assertEquals(listOf("export-1.log", "export-2.log", "import-2.log", "import-3.txt", "notes.txt"), names())
    }

    @Test
    fun `does nothing under the limit and on a missing folder`() {
        touch("import-1.log")
        LogFiles.rotate(tmp.root.toPath(), "import", 5)
        assertEquals(listOf("import-1.log"), names())

        LogFiles.rotate(tmp.root.toPath().resolve("missing"), "import", 1)
        assertTrue(Files.exists(tmp.root.toPath()))
    }
}
