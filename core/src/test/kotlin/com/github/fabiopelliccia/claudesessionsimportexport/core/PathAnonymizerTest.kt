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
        // A home inside a longer path is replaced too: privacy wins over a cosmetic false positive.
        assertEquals("/data{{SP:HOME:fwd}}/x", posix.anonymizeText("/data/home/alice/x"))
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

    // ---------------------------------------------------- review of the engine (REVIEW-WP3.md) --

    private val pelliccia = PathAnonymizer(SourceIdentity(home = """C:\Users\pelliccia"""))
    private val macAlice = PathAnonymizer(SourceIdentity(home = "/Users/alice", roots = listOf("/Users/alice/proj")))

    // Finding 1: the 8.3 home in the other spellings, not only as "<drive>:" plus separators.
    @Test
    fun `finding 1 - a short 8-3 home in Git Bash, WSL, Cygwin, encoded and eight-backslash spellings`() {
        assertEquals(
            "{{SP:HOME:msys}}/AppData/Local/Temp/claude/{{SP:HOME:enc}}-IdeaProjects-x/scratchpad",
            pelliccia.anonymizeText("/c/Users/PELLIC~1/AppData/Local/Temp/claude/C--Users-pelliccia-IdeaProjects-x/scratchpad"),
        )
        assertEquals("{{SP:HOME:wsl}}/AppData", anonymizer.anonymizeText("/mnt/c/Users/ALICE~1/AppData"))
        assertEquals("{{SP:HOME:cyg}}/AppData", anonymizer.anonymizeText("/cygdrive/c/Users/ALICE~1/AppData"))
        assertEquals("{{SP:HOME:enc}}-AppData-Local-Temp-x", pelliccia.anonymizeText("C--Users-PELLIC-1-AppData-Local-Temp-x"))
        assertEquals("""{{SP:HOME:esc3}}\\\\\\\\x""", anonymizer.anonymizeText("""C:\\\\\\\\Users\\\\\\\\ALICE~1\\\\\\\\x"""))
    }

    @Test
    fun `finding 1 - a short home read from a temp folder is found in the Git Bash spelling too`() {
        val withExplicitShort = PathAnonymizer(SourceIdentity(home = home, shortHomes = listOf("""C:\Users\ZO5F2~1""")))
        assertEquals("{{SP:HOME:msys}}/AppData", withExplicitShort.anonymizeText("/c/Users/ZO5F2~1/AppData"))
        assertEquals("{{SP:HOME:enc}}-AppData", withExplicitShort.anonymizeText("C--Users-ZO5F2-1-AppData"))
    }

    // Finding 2: a letter or digit before the anchor must not hide it.
    @Test
    fun `finding 2 - an anchor glued to a flag, an ANSI colour or a longer path is still found`() {
        assertEquals("""gcc -I{{SP:HOME:win}}\inc""", anonymizer.anonymizeText("""gcc -IC:\Users\alice\inc"""))
        // A real ESC character, and the same colour as a JSON writer spells it, seen in raw text.
        assertEquals("\u001b[34m{{SP:HOME:win}}\\proj\u001b[0m", anonymizer.anonymizeText("\u001b[34mC:\\Users\\alice\\proj\u001b[0m"))
        assertEquals("""\u001b[34m{{SP:HOME:win}}\proj""", anonymizer.anonymizeText("""\u001b[34mC:\Users\alice\proj"""))
        assertEquals("/System/Volumes/Data{{SP:ROOT:fwd}}/a", macAlice.anonymizeText("/System/Volumes/Data/Users/alice/proj/a"))
        assertEquals("docker: /host_mnt{{SP:ROOT:fwd}}", macAlice.anonymizeText("docker: /host_mnt/Users/alice/proj"))
        assertEquals("clang -I{{SP:HOME:fwd}}/include", macAlice.anonymizeText("clang -I/Users/alice/include"))
        assertEquals("/var{{SP:HOME:fwd}}/x", PathAnonymizer(SourceIdentity(home = "/home/alice")).anonymizeText("/var/home/alice/x"))
        assertEquals("file%3A%2F%2F{{SP:HOME:url}}%2Fx", macAlice.anonymizeText("file%3A%2F%2F%2FUsers%2Falice%2Fx"))
    }

    @Test
    fun `finding 2 - a one-folder anchor keeps its boundaries, relaxed for a flag and a colour`() {
        val oneFolder = PathAnonymizer(SourceIdentity(home = "/home/alice", roots = listOf("/work")))
        assertEquals("{{SP:ROOT:fwd}}/x", oneFolder.anonymizeText("/work/x"))
        assertEquals("/usr/network/work/x", oneFolder.anonymizeText("/usr/network/work/x"))
        assertEquals("cc -I{{SP:ROOT:fwd}}/x", oneFolder.anonymizeText("cc -I/work/x"))
    }

    // Finding 9: any mix of separators between the folders, the first one gives the style.
    @Test
    fun `finding 9 - mixed and escaped separators are matched and the first one gives the style`() {
        assertEquals("""{{SP:HOME:fwd}}\/x""", anonymizer.anonymizeText("""C:\/Users\/alice\/x"""))
        assertEquals("{{SP:HOME:esc1}}", anonymizer.anonymizeText("""C:\\Users/alice"""))
        assertEquals("{{SP:HOME:win}}", anonymizer.anonymizeText("""C:\Users\\alice"""))
        assertEquals("{{SP:HOME:fwd}}", anonymizer.anonymizeText("C://Users//alice"))
        assertEquals("""{{SP:HOME:esc1}}\x""", anonymizer.anonymizeText("""C:\u005cUsers\u005calice\x"""))
    }

    // Finding 11: the Windows 8.3 rule for a dotted name, alice.smith -> ALICE~1.SMI.
    @Test
    fun `finding 11 - the 8-3 name of a dotted user name carries an extension`() {
        val dotted = SourceIdentity(home = """C:\Users\alice.smith""")
        assertEquals("ALICE", dotted.shortStem)
        val dottedAnonymizer = PathAnonymizer(dotted)
        assertEquals("""{{SP:HOME:short}}\AppData""", dottedAnonymizer.anonymizeText("""C:\Users\ALICE~1.SMI\AppData"""))
        assertEquals("{{SP:HOME:msys}}/AppData", dottedAnonymizer.anonymizeText("/c/Users/ALICE~1.SMI/AppData"))
        // The engine's former guess, the first six letters of the whole name, is still accepted.
        assertEquals("""{{SP:HOME:short}}\x""", dottedAnonymizer.anonymizeText("""C:\Users\ALICES~1\x"""))
        // The residual check looks at the dot-separated parts of the name.
        val stats = PathAnonymizer.Stats()
        dottedAnonymizer.anonymizeText("alice pushed users/alice/fix", stats)
        assertEquals(2, stats.residualUserNameHits)
    }

    // Finding 14: a hyphen followed by a name character belongs to the name, in the plain styles.
    @Test
    fun `finding 14 - a name continued by a hyphen is another folder`() {
        assertEquals(
            """{{SP:HOME:win}}\IdeaProjects\X-v2\a.txt""",
            anonymizer.anonymizeText("""C:\Users\alice\IdeaProjects\X-v2\a.txt"""),
        )
        val stats = PathAnonymizer.Stats()
        assertEquals("""C:\Users\alice-admin\a.txt""", anonymizer.anonymizeText("""C:\Users\alice-admin\a.txt""", stats))
        // Not silent: the bare name is reported.
        assertEquals(1, stats.residualUserNameHits)
        // A hyphen that does not continue the name is a boundary.
        assertEquals("{{SP:HOME:win}}- x", anonymizer.anonymizeText("""C:\Users\alice- x"""))
        // The encoded form joins its folders with hyphens, so there a hyphen is still a boundary.
        assertEquals("{{SP:HOME:enc}}-Other", anonymizer.anonymizeText("C--Users-alice-Other"))
    }

    // Finding 16: the encoded form after a literal "\n" of raw JSON text.
    @Test
    fun `finding 16 - an encoded project folder after a json newline escape is found in raw text`() {
        assertEquals("""ls\n{{SP:ROOT:enc}}\n""", anonymizer.anonymizeText("""ls\nC--Users-alice-IdeaProjects-X\n"""))
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
