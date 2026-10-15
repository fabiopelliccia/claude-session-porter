package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgumentParserTest {

    private fun parse(vararg args: String) = ArgumentParser.parse(args.toList())

    private fun assertUsage(code: String, vararg args: String) {
        val error = assertThrows(CliException::class.java) { parse(*args) }
        assertEquals(ExitCode.USAGE, error.exitCode)
        assertEquals(code, error.code)
    }

    @Test
    fun `global options may stand before or after the command`() {
        val before = parse("--json", "--home", "/h", "list")
        val after = parse("list", "--json", "--home=/h")
        for (line in listOf(before, after)) {
            assertEquals("list", line.command)
            assertTrue(line.flag("--json"))
            assertEquals("/h", line.value("--home"))
        }
    }

    @Test
    fun `a value follows its option as the next word or after an equals sign, and may start with a dash`() {
        val line = parse("export", "--ids-file", "-", "--out=archive.zip")
        assertEquals("-", line.value("--ids-file"))
        assertEquals("archive.zip", line.value("--out"))
    }

    @Test
    fun `positional arguments and the double dash`() {
        assertEquals(listOf("a.zip"), parse("inspect", "a.zip").positionals)
        assertEquals(listOf("--odd.zip"), parse("inspect", "--", "--odd.zip").positionals)
        assertEquals(listOf("a.zip"), parse("import", "a.zip", "--target", "/x").positionals)
    }

    @Test
    fun `--ids may be repeated, any other option may not`() {
        val line = parse("export", "--ids", "a,b", "--ids", "c", "--out", "x.zip", "--out", "y.zip")
        assertEquals(listOf("a,b", "c"), line.values("--ids"))
        val error = assertThrows(CliException::class.java) { line.value("--out") }
        assertEquals("repeatedOption", error.code)
    }

    @Test
    fun `flags are known only to the commands that have them`() {
        assertTrue(parse("export", "--all", "--force", "--strict-privacy", "--out", "x.zip").flag("--force"))
        assertFalse(parse("export", "--all", "--out", "x.zip").flag("--force"))
        assertUsage("unknownOption", "list", "--force")
        assertUsage("unknownOption", "import", "a.zip", "--all")
    }

    @Test
    fun `help is asked for with --help, -h or anywhere in the line`() {
        assertEquals("help", parse("--help").command)
        assertEquals("help", parse("export", "-h").command)
        assertEquals("help", parse("help").command)
    }

    @Test
    fun `usage mistakes are told apart by code`() {
        assertUsage("noCommand")
        assertUsage("noCommand", "--json")
        assertUsage("unknownCommand", "frobnicate")
        assertUsage("unknownOption", "list", "--nope")
        assertUsage("unknownOption", "--nope", "list")
        assertUsage("missingValue", "list", "--limit")
        assertUsage("unexpectedValue", "list", "--json=yes")
        assertUsage("missingArgument", "inspect")
        assertUsage("unexpectedArgument", "inspect", "a.zip", "b.zip")
        assertUsage("unexpectedArgument", "list", "extra")
    }

    @Test
    fun `no option is given when none was`() {
        val line = parse("list")
        assertNull(line.value("--limit"))
        assertEquals(emptyList<String>(), line.values("--ids"))
        assertFalse(line.isGiven("--project"))
    }
}
