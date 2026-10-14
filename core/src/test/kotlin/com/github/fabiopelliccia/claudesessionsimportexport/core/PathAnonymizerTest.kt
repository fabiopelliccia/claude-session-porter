package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anonymization engine at string and line level: every spelling of the anchors, boundaries, the
 * JSON walk (values and keys), line endings and the cheap checks (binary scan, residual user name).
 *
 * Every identity is injected: the real home of the machine running the tests is never a fixture.
 */
class PathAnonymizerTest {

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private val home = """C:\Users\alice"""
    private val root = """C:\Users\alice\IdeaProjects\X"""
    private val alice = SourceIdentity(home = home, roots = listOf(root))
    private val anonymizer = PathAnonymizer(alice)

    private fun forms(base: String): Map<String, String> {
        val rest = base.substring(2)
        val drive = base[0]
        return linkedMapOf(
            "win" to base,
            "fwd" to base.replace('\\', '/'),
            "esc1" to base.replace("\\", "\\\\"),
            "esc2" to base.replace("\\", "\\\\\\\\"),
            "msys" to "/" + drive.lowercaseChar() + rest.replace('\\', '/'),
            "wsl" to "/mnt/" + drive.lowercaseChar() + rest.replace('\\', '/'),
            "cyg" to "/cygdrive/" + drive.lowercaseChar() + rest.replace('\\', '/'),
            "enc" to ClaudePaths.encodeProjectPath(base),
            "url" to base.replace("\\", "%2F").replace(":", "%3A"),
            "urlbs" to base.replace("\\", "%5C").replace(":", "%3A"),
        )
    }

    // ---------------------------------------------------------------------------------- styles --

    @Test
    fun `every style of the home is replaced by its own token`() {
        for ((style, text) in forms(home)) {
            assertEquals("style $style", "{{SP:HOME:$style}} end", anonymizer.anonymizeText("$text end"))
        }
    }

    @Test
    fun `every style of a project root is replaced by its own token`() {
        for ((style, text) in forms(root)) {
            assertEquals("style $style", "{{SP:ROOT:$style}} end", anonymizer.anonymizeText("$text end"))
        }
    }

    @Test
    fun `what follows the anchor is kept as it was`() {
        assertEquals("""{{SP:HOME:win}}\note.txt""", anonymizer.anonymizeText("""C:\Users\alice\note.txt"""))
        assertEquals("""file:///{{SP:HOME:fwd}}/note.txt""", anonymizer.anonymizeText("file:///C:/Users/alice/note.txt"))
        assertEquals("""{{SP:HOME:esc1}}\\note.txt""", anonymizer.anonymizeText("""C:\\Users\\alice\\note.txt"""))
        assertEquals("""{{SP:ROOT:win}}\src/Main.kt""", anonymizer.anonymizeText("""C:\Users\alice\IdeaProjects\X\src/Main.kt"""))
    }

    @Test
    fun `a short 8-3 home is found by the generic fallback`() {
        assertEquals("""{{SP:HOME:short}}\AppData""", anonymizer.anonymizeText("""C:\Users\ALICE~1\AppData"""))
        assertEquals("""{{SP:HOME:short1}}\\AppData""", anonymizer.anonymizeText("""C:\\Users\\ALICE~12\\AppData"""))
        assertEquals("{{SP:HOME:shortfwd}}/AppData", anonymizer.anonymizeText("C:/Users/alice~1/AppData"))
    }

    @Test
    fun `a short home read from an injected temp folder is found even when its stem is not the user name`() {
        val temp = """C:\Users\ZO5F2~1\AppData\Local\Temp"""
        val shorts = SourceIdentity.shortFormsOf(home, listOf(temp, """C:\Users\alice\AppData\Local\Temp""", "")) {
            """C:\Users\alice\AppData\Local\Temp"""
        }
        assertEquals(listOf("""C:\Users\ZO5F2~1"""), shorts)

        val identity = SourceIdentity(home = home, shortHomes = shorts)
        assertEquals("""{{SP:HOME:short}}\AppData""", PathAnonymizer(identity).anonymizeText("""c:\users\zo5f2~1\AppData"""))
    }

    @Test
    fun `a posix home is found in its plain, encoded and percent spellings`() {
        val posix = PathAnonymizer(SourceIdentity(home = "/home/alice", roots = listOf("/home/alice/proj/demo")))
        assertEquals("{{SP:HOME:fwd}}/x", posix.anonymizeText("/home/alice/x"))
        assertEquals("{{SP:ROOT:fwd}}/src", posix.anonymizeText("/home/alice/proj/demo/src"))
        assertEquals("{{SP:ROOT:enc}}", posix.anonymizeText("-home-alice-proj-demo"))
        assertEquals("{{SP:HOME:enc}}-other", posix.anonymizeText("-home-alice-other"))
        assertEquals("{{SP:HOME:url}}%2Fx", posix.anonymizeText("%2Fhome%2Falice%2Fx"))
        // Somebody else's folder that merely ends the same way is not the home.
        assertEquals("/data/home/alice/x", posix.anonymizeText("/data/home/alice/x"))
    }

    // ------------------------------------------------------------------------------ precedence --

    @Test
    fun `the project root wins over the home it lives in`() {
        assertEquals(
            """{{SP:ROOT:win}}\src and {{SP:HOME:win}}\IdeaProjects\Y\src""",
            anonymizer.anonymizeText("""C:\Users\alice\IdeaProjects\X\src and C:\Users\alice\IdeaProjects\Y\src"""),
        )
    }

    @Test
    fun `an encoded project folder is the root for the root and the home for any other folder`() {
        assertEquals("{{SP:ROOT:enc}}", anonymizer.anonymizeText("C--Users-alice-IdeaProjects-X"))
        assertEquals("{{SP:HOME:enc}}-Other", anonymizer.anonymizeText("C--Users-alice-Other"))
        assertEquals("{{SP:HOME:enc}}-IdeaProjects-XY", anonymizer.anonymizeText("C--Users-alice-IdeaProjects-XY"))
        assertEquals("projects/{{SP:ROOT:enc}}/s.jsonl", anonymizer.anonymizeText("projects/C--Users-alice-IdeaProjects-X/s.jsonl"))
    }

    @Test
    fun `a root outside the home is anchored on its own`() {
        val outside = PathAnonymizer(SourceIdentity(home = home, roots = listOf("""D:\work\demo""")))
        assertEquals("""{{SP:ROOT:win}}\src""", outside.anonymizeText("""D:\work\demo\src"""))
        assertEquals("{{SP:ROOT:msys}}/src", outside.anonymizeText("/d/work/demo/src"))
        assertEquals("{{SP:ROOT:enc}}", outside.anonymizeText("D--work-demo"))
        assertEquals("""D:\work\other""", outside.anonymizeText("""D:\work\other"""))
    }

    @Test
    fun `a relocated Claude home is an anchor and one below the home is not`() {
        val relocated = SourceIdentity(home = home, claudeHome = """D:\claude-data""")
        assertEquals("""D:\claude-data""", relocated.claudeHome)
        assertEquals("""{{SP:CLAUDE_HOME:win}}\projects""", PathAnonymizer(relocated).anonymizeText("""D:\claude-data\projects"""))

        val inside = SourceIdentity(home = home, claudeHome = """C:\Users\alice\.claude""")
        assertEquals(null, inside.claudeHome)
        assertEquals("""{{SP:HOME:win}}\.claude\projects""", PathAnonymizer(inside).anonymizeText("""C:\Users\alice\.claude\projects"""))
    }

    @Test
    fun `a root that is the home itself is the root`() {
        val same = PathAnonymizer(SourceIdentity(home = home, roots = listOf(home)))
        assertEquals("""{{SP:ROOT:win}}\a.txt""", same.anonymizeText("""C:\Users\alice\a.txt"""))
        val stats = PathAnonymizer.Stats()
        same.anonymizeText("""C:\Users\alice""", stats)
        assertEquals(mapOf("ROOT" to 1), stats.replacedByKind)
    }

    // ------------------------------------------------------------------------------- boundaries --

    @Test
    fun `a longer name is not the user and the case does not matter`() {
        assertEquals("""C:\Users\alice2\x""", anonymizer.anonymizeText("""C:\Users\alice2\x"""))
        assertEquals("""C:\Users\alice.bak\x""", anonymizer.anonymizeText("""C:\Users\alice.bak\x"""))
        assertEquals("""C:\Users\alice_old""", anonymizer.anonymizeText("""C:\Users\alice_old"""))
        assertEquals("{{SP:HOME:win}}.", anonymizer.anonymizeText("""C:\Users\alice."""))
        assertEquals("""{{SP:HOME:win}}\x""", anonymizer.anonymizeText("""C:\Users\Alice\x"""))
        assertEquals("""{{SP:HOME:win}}\x""", anonymizer.anonymizeText("""c:\USERS\ALICE\x"""))
        assertEquals("C--Users-alice2", anonymizer.anonymizeText("C--Users-alice2"))
    }

    @Test
    fun `an anchor right after a json newline escape is found in raw text`() {
        assertEquals("""line\n{{SP:HOME:esc1}}\\x""", anonymizer.anonymizeText("""line\nC:\\Users\\alice\\x"""))
    }

    @Test
    fun `a name with spaces and non-ascii letters is found in every spelling`() {
        val zoe = PathAnonymizer(SourceIdentity(home = "C:\\Users\\Zo\u00eb M\u00fcller"))
        assertEquals("C--Users-Zo--M-ller", ClaudePaths.encodeProjectPath("C:\\Users\\Zo\u00eb M\u00fcller"))
        assertEquals("""{{SP:HOME:win}}\x""", zoe.anonymizeText("C:\\Users\\Zo\u00eb M\u00fcller\\x"))
        assertEquals("{{SP:HOME:enc}}-IdeaProjects", zoe.anonymizeText("C--Users-Zo--M-ller-IdeaProjects"))
        assertEquals("file:///{{SP:HOME:fwd}}/x", zoe.anonymizeText("file:///C:/Users/Zo\u00eb M\u00fcller/x"))
        assertEquals("file:///{{SP:HOME:url}}/x", zoe.anonymizeText("file:///C:/Users/Zo%C3%AB%20M%C3%BCller/x"))
        // A writer that escapes non-ASCII: the cheap pre-filter must not skip the line.
        val raw = """{"p":"C:\\Users\\Zo\u00eb M\u00fcller\\x"}"""
        assertEquals("""{"p":"{{SP:HOME:win}}\\x"}""", zoe.anonymizeJsonLine(raw))
    }

    // ------------------------------------------------------------------------------------ json --

    @Test
    fun `keys of trackedFileBackups are rewritten along with the values`() {
        val line = gson.toJson(
            mapOf(
                "type" to "file-history-snapshot",
                "snapshot" to mapOf(
                    "trackedFileBackups" to mapOf(
                        """$root\src\Main.kt""" to mapOf("backupFileName" to "m@v1", "realParentDir" to """$root\src"""),
                        """$home\note.txt""" to mapOf("backupFileName" to "n@v1"),
                    ),
                ),
            ),
        )
        val stats = PathAnonymizer.Stats()
        val out = anonymizer.anonymizeJsonLine(line, stats)

        assertFalse(out.contains("alice", ignoreCase = true))
        val backups = JsonParser.parseString(out).asJsonObject.getAsJsonObject("snapshot").getAsJsonObject("trackedFileBackups")
        assertEquals(listOf("""{{SP:ROOT:win}}\src\Main.kt""", """{{SP:HOME:win}}\note.txt"""), backups.keySet().toList())
        assertEquals("""{{SP:ROOT:win}}\src""", backups.getAsJsonObject("""{{SP:ROOT:win}}\src\Main.kt""").get("realParentDir").asString)
        assertEquals(1, stats.rewrittenLines)
    }

    @Test
    fun `two keys that would collapse into one keep the later name and are counted`() {
        val line = gson.toJson(mapOf("""C:\Users\alice\a""" to 1, """C:\Users\ALICE\a""" to 2))
        val stats = PathAnonymizer.Stats()
        val out = JsonParser.parseString(anonymizer.anonymizeJsonLine(line, stats)).asJsonObject
        assertEquals(setOf("""{{SP:HOME:win}}\a""", """C:\Users\ALICE\a"""), out.keySet())
        assertEquals(1, stats.keyCollisions)
    }

    @Test
    fun `a line without an anchor comes back as the same string`() {
        val lines = listOf(
            """{ "a" : "caf\u00e9",  "b":[1,2.50,null] }""",
            """{"message":"sono alice","gitBranch":"users/alice/fix"}""",
            "not json at all",
            """{"cwd":"C:\\Users\\bob\\x"}""",
        )
        for (line in lines) assertSame(line, anonymizer.anonymizeJsonLine(line))
    }

    @Test
    fun `a changed line is re-emitted like Claude Code writes it`() {
        val line = """{"parentUuid":null,"text":"a < b & c","cwd":"C:\\Users\\alice\\IdeaProjects\\X"}"""
        assertEquals("""{"parentUuid":null,"text":"a < b & c","cwd":"{{SP:ROOT:win}}"}""", anonymizer.anonymizeJsonLine(line))
    }

    @Test
    fun `a json document stored in a string is one escaping level deeper`() {
        val inner = gson.toJson(mapOf("cwd" to """C:\Users\alice\p"""))
        val outer = gson.toJson(mapOf("content" to inner))
        val twice = gson.toJson(mapOf("content" to gson.toJson(mapOf("content" to inner))))

        val once = JsonParser.parseString(anonymizer.anonymizeJsonLine(outer)).asJsonObject.get("content").asString
        assertEquals("""{"cwd":"{{SP:HOME:esc1}}\\p"}""", once)

        val nested = JsonParser.parseString(anonymizer.anonymizeJsonLine(twice)).asJsonObject.get("content").asString
        assertEquals("""{"content":"{\"cwd\":\"{{SP:HOME:esc2}}\\\\p\"}"}""", nested)
    }

    @Test
    fun `a line that is not json goes through the raw text pass at the raw level`() {
        val partial = """{"cwd":"C:\\Users\\alice\\IdeaProjects\\X","text":"C:\\Users\\alice\\n"""
        val stats = PathAnonymizer.Stats()
        assertEquals("""{"cwd":"{{SP:ROOT:esc1}}","text":"{{SP:HOME:esc1}}\\n""", anonymizer.anonymizeJsonLine(partial, stats))
        assertEquals(1, stats.unparsableLines)
    }

    // ----------------------------------------------------------------------------- whole files --

    @Test
    fun `line endings, blank lines and a missing final newline are preserved`() {
        val a = gson.toJson(mapOf("cwd" to root))
        val b = gson.toJson(mapOf("keep" to "me"))
        val crlf = "$a\r\n\r\n$b\r\n$a"
        val out = anonymizer.anonymizeJsonl(crlf)
        assertEquals("""{"cwd":"{{SP:ROOT:win}}"}""" + "\r\n\r\n" + b + "\r\n" + """{"cwd":"{{SP:ROOT:win}}"}""", out)

        val lf = "$a\n$b\n"
        assertEquals("""{"cwd":"{{SP:ROOT:win}}"}""" + "\n" + b + "\n", anonymizer.anonymizeJsonl(lf))
        assertSame(b, anonymizer.anonymizeJsonl(b))
        val untouched = "$b\r\n$b"
        assertSame(untouched, anonymizer.anonymizeJsonl(untouched))
    }

    // ---------------------------------------------------------------------------- cheap checks --

    @Test
    fun `the bare user name left after the replacement is counted`() {
        val stats = PathAnonymizer.Stats()
        val text = """sono alice, users/alice/fix, alice@host, alice2, Malice, C:\Users\alice\x"""
        val out = anonymizer.anonymizeText(text, stats)
        assertEquals("""sono alice, users/alice/fix, alice@host, alice2, Malice, {{SP:HOME:win}}\x""", out)
        assertEquals(3, stats.residualUserNameHits)
        assertEquals(3, anonymizer.countResidualUserName(out))
    }

    @Test
    fun `a user name shorter than four characters is not counted`() {
        val bob = PathAnonymizer(SourceIdentity(home = """C:\Users\bob"""))
        val stats = PathAnonymizer.Stats()
        bob.anonymizeText("hello bob", stats)
        assertEquals(0, stats.residualUserNameHits)
    }

    @Test
    fun `replacements are counted by kind and by style`() {
        val stats = PathAnonymizer.Stats()
        anonymizer.anonymizeText("""C:\Users\alice\a C:/Users/alice/b $root\c C--Users-alice-IdeaProjects-X""", stats)
        assertEquals(mapOf("HOME" to 2, "ROOT" to 2), stats.replacedByKind)
        assertEquals(mapOf("enc" to 1, "fwd" to 1, "win" to 2), stats.replacedByStyle)
        assertEquals(4, stats.totalReplacements)
    }

    @Test
    fun `a binary file is scanned in utf-8 and in utf-16le`() {
        assertEquals(1, anonymizer.scanBytes("""xx C:\Users\alice\x yy""".toByteArray(Charsets.UTF_8)))
        val wide = """xx C:\Users\alice\x yy""".toByteArray(Charsets.UTF_16LE)
        assertEquals(1, anonymizer.scanBytes(wide))
        assertEquals(1, anonymizer.scanBytes(byteArrayOf(0x7f) + wide))
        assertEquals(0, anonymizer.scanBytes(ByteArray(1024) { (it * 31).toByte() }))
        assertEquals(0, anonymizer.scanBytes(ByteArray(0)))
    }

    // ------------------------------------------------------------------------------ performance --

    /**
     * PERFORMANCE BOUND - tune [limitMillis] if the build machine is slower: about 50 MB of JSONL
     * where one line in 400 holds an anchor must go through in well under it (measured: a few seconds).
     */
    @Test
    fun `fifty megabytes of mostly unrelated transcript are processed within the time bound`() {
        val limitMillis = 20_000L
        val filler = "lorem ipsum dolor sit amet ".repeat(36)
        val plain = gson.toJson(mapOf("type" to "assistant", "text" to filler, "n" to 1))
        val withPath = gson.toJson(mapOf("type" to "user", "text" to filler, "cwd" to root))
        val text = StringBuilder(52_000_000)
        var lines = 0
        var withPaths = 0
        while (text.length < 50_000_000) {
            if (lines % 400 == 0) {
                text.append(withPath)
                withPaths++
            } else {
                text.append(plain)
            }
            text.append('\n')
            lines++
        }
        val input = text.toString()

        val stats = PathAnonymizer.Stats()
        val started = System.nanoTime()
        val out = anonymizer.anonymizeJsonl(input, stats)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(withPaths, stats.rewrittenLines)
        assertEquals(withPaths, stats.replacedByKind["ROOT"])
        assertFalse(out.contains("alice"))
        assertTrue("took $elapsedMillis ms for $lines lines", elapsedMillis < limitMillis)
    }
}
