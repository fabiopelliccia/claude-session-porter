package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * The other half of the engine: tokens rendered for a destination, and the whole round trip
 * (anonymize on PC_1, deanonymize on PC_2) for every spelling, across operating systems, with a
 * source that already holds a literal token, and through JSON documents stored in strings.
 */
class PathDeanonymizerTest {

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private val aliceRoot = """C:\Users\alice\IdeaProjects\X"""
    private val alice = SourceIdentity(home = """C:\Users\alice""", roots = listOf(aliceRoot))
    private val anonymizer = PathAnonymizer(alice)

    private val bobRoot = """C:\Users\bob\work\Y"""
    private val bob = PathDeanonymizer(root = bobRoot, home = """C:\Users\bob""", claudeHome = """C:\Users\bob\.claude""")

    private fun roundTrip(text: String, from: PathAnonymizer = anonymizer, to: PathDeanonymizer = bob): String =
        to.deanonymizeText(from.anonymizeText(text))

    // ---------------------------------------------------------------------------------- tokens --

    @Test
    fun `a token is rendered and parsed back`() {
        assertEquals("{{SP:HOME:win}}", PathTokens.render(PathKind.HOME, PathStyle.WIN))
        assertEquals("{{SP7f3a:ROOT:esc2}}", PathTokens.render(PathKind.ROOT, PathStyle.ESC2, "SP7f3a"))
        assertEquals(PathToken(PathKind.CLAUDE_HOME, PathStyle.SHORT_ESC1), PathTokens.parse("{{SP:CLAUDE_HOME:short1}}"))
        assertNull(PathTokens.parse("{{SP:HOME:win}}x"))
        assertNull(PathTokens.parse("{{SP:NOWHERE:win}}"))
        assertNull(PathTokens.parse("{{SP7f3a:HOME:win}}"))
        for (kind in PathKind.entries) for (style in PathStyle.entries) {
            assertEquals(PathToken(kind, style), PathTokens.parse(PathTokens.render(kind, style)))
        }
    }

    @Test
    fun `the prefix is the default one unless the source already holds the marker`() {
        assertEquals("SP", PathTokens.choosePrefix { false })
        val nonce = PathTokens.choosePrefix { it == "{{SP" }
        assertTrue(nonce, Regex("SP[0-9a-f]{4}").matches(nonce))
        val tried = mutableListOf<String>()
        PathTokens.choosePrefix { tried += it; tried.size < 3 }
        assertEquals("{{SP", tried.first())
        assertTrue(tried.drop(1).all { it.startsWith("{{SP") && it.length == 8 })
        assertEquals(2, PathTokens.count("{{SP:HOME:win}} {{SP:ROOT:enc}} {{SP7f3a:HOME:win}}"))
    }

    // ----------------------------------------------------------------------------------- render --

    @Test
    fun `a windows destination renders every style`() {
        val expected = mapOf(
            PathStyle.WIN to """C:\Users\bob""",
            PathStyle.FWD to "C:/Users/bob",
            PathStyle.ESC1 to """C:\\Users\\bob""",
            PathStyle.ESC2 to """C:\\\\Users\\\\bob""",
            PathStyle.MSYS to "/c/Users/bob",
            PathStyle.WSL to "/mnt/c/Users/bob",
            PathStyle.CYG to "/cygdrive/c/Users/bob",
            PathStyle.SHORT to """C:\Users\bob""",
            PathStyle.SHORT_FWD to "C:/Users/bob",
            PathStyle.SHORT_ESC1 to """C:\\Users\\bob""",
            PathStyle.ENC to "C--Users-bob",
            PathStyle.URL to "C:/Users/bob",
            PathStyle.URL_BACKSLASH to "C%3A%5CUsers%5Cbob",
        )
        for ((style, text) in expected) assertEquals("style $style", text, bob.render(PathKind.HOME, style))
        assertEquals("C--Users-bob-work-Y", bob.render(PathKind.ROOT, PathStyle.ENC))
        assertEquals("""C:\Users\bob\.claude""", bob.render(PathKind.CLAUDE_HOME, PathStyle.WIN))
    }

    @Test
    fun `a posix destination renders the native path for every windows only style`() {
        val mac = PathDeanonymizer(root = "/Users/bob/proj", home = "/Users/bob", claudeHome = "/Users/bob/.claude")
        for (style in listOf(PathStyle.WIN, PathStyle.FWD, PathStyle.ESC1, PathStyle.ESC2, PathStyle.MSYS, PathStyle.WSL, PathStyle.CYG, PathStyle.SHORT)) {
            assertEquals("style $style", "/Users/bob", mac.render(PathKind.HOME, style))
        }
        assertEquals("-Users-bob-proj", mac.render(PathKind.ROOT, PathStyle.ENC))
        assertEquals("/Users/bob", mac.render(PathKind.HOME, PathStyle.URL))
    }

    @Test
    fun `a name with spaces and non-ascii letters is percent-encoded and encoded`() {
        val zoe = PathDeanonymizer(root = null, home = "C:\\Users\\Zo\u00eb M\u00fcller", claudeHome = "C:\\Users\\Zo\u00eb M\u00fcller\\.claude")
        assertEquals("C:/Users/Zo%C3%AB%20M%C3%BCller", zoe.render(PathKind.HOME, PathStyle.URL))
        assertEquals("C--Users-Zo--M-ller", zoe.render(PathKind.HOME, PathStyle.ENC))
        assertEquals("C:\\Users\\Zo\u00eb M\u00fcller", zoe.render(PathKind.HOME, PathStyle.WIN))
    }

    @Test
    fun `a token without a destination is left in place and counted`() {
        val noRoot = PathDeanonymizer(root = null, home = """C:\Users\bob""", claudeHome = """C:\Users\bob\.claude""")
        val stats = PathDeanonymizer.Stats()
        assertEquals("""{{SP:ROOT:win}}\src C:\Users\bob""", noRoot.deanonymizeText("""{{SP:ROOT:win}}\src {{SP:HOME:win}}""", stats))
        assertEquals(1, stats.unresolvedTokens)
        assertEquals(1, stats.totalRendered)
    }

    // ------------------------------------------------------------------------------- round trip --

    @Test
    fun `every spelling of alice's home comes back as bob's equivalent`() {
        val cases = listOf(
            """C:\Users\alice\note.txt""" to """C:\Users\bob\note.txt""",
            "C:/Users/alice/note.txt" to "C:/Users/bob/note.txt",
            "file:///C:/Users/alice/note.txt" to "file:///C:/Users/bob/note.txt",
            """C:\\Users\\alice\\note.txt""" to """C:\\Users\\bob\\note.txt""",
            """C:\\\\Users\\\\alice\\\\note.txt""" to """C:\\\\Users\\\\bob\\\\note.txt""",
            "/c/Users/alice/note.txt" to "/c/Users/bob/note.txt",
            "/mnt/c/Users/alice/note.txt" to "/mnt/c/Users/bob/note.txt",
            "/cygdrive/c/Users/alice/note.txt" to "/cygdrive/c/Users/bob/note.txt",
            """C:\Users\ALICE~1\AppData\Local\Temp""" to """C:\Users\bob\AppData\Local\Temp""",
            "C--Users-alice-Other" to "C--Users-bob-Other",
            "C%3A%5CUsers%5Calice%5Cnote.txt" to "C%3A%5CUsers%5Cbob%5Cnote.txt",
            "C:%2FUsers%2Falice%2Fnote.txt" to "C:/Users/bob%2Fnote.txt",
            """c:\users\Alice\note.txt""" to """C:\Users\bob\note.txt""",
        )
        for ((source, expected) in cases) assertEquals(source, expected, roundTrip(source))
    }

    // Finding 8: the home without its drive, and a UNC home, come back as the destination's.
    @Test
    fun `a home without its drive letter comes back without it, at its own escaping level`() {
        assertEquals("HOMEPATH=" + """\Users\bob""", roundTrip("""HOMEPATH=\Users\alice"""))
        assertEquals("""\\localhost\c$""" + """\Users\bob\x""", roundTrip("""\\localhost\c$\Users\alice\x"""))
        assertEquals("""\\Users\\bob\\x""", roundTrip("""\\Users\\alice\\x"""))
        assertEquals("""\\\\Users\\\\bob""", roundTrip("""\\\\Users\\\\alice"""))
        val mac = PathDeanonymizer(root = "/Users/bob/proj", home = "/Users/bob", claudeHome = "/Users/bob/.claude")
        assertEquals("HOMEPATH=/Users/bob", roundTrip("""HOMEPATH=\Users\alice""", to = mac))
        assertEquals("/Users/bob/x/y", roundTrip("""\Users\alice\x\y""", to = mac))
    }

    @Test
    fun `a UNC home of the source comes back as the destination's home`() {
        val source = PathAnonymizer(SourceIdentity(home = """\\fileserver\users\alice"""))
        assertEquals("""C:\Users\bob\docs""", roundTrip("""\\fileserver\users\alice\docs""", source))
        assertEquals("""C:\\Users\\bob\\docs""", roundTrip("""\\\\fileserver\\users\\alice\\docs""", source))
        assertEquals("C--Users-bob-docs", roundTrip("--fileserver-users-alice-docs", source))
    }

    @Test
    fun `the project root comes back as the target folder`() {
        assertEquals("""C:\Users\bob\work\Y\src\a.kt""", roundTrip("""C:\Users\alice\IdeaProjects\X\src\a.kt"""))
        assertEquals("C--Users-bob-work-Y", roundTrip("C--Users-alice-IdeaProjects-X"))
        assertEquals("""C:\\Users\\bob\\work\\Y\\src""", roundTrip("""C:\\Users\\alice\\IdeaProjects\\X\\src"""))
        assertEquals("""C:\Users\bob\IdeaProjects\Z""", roundTrip("""C:\Users\alice\IdeaProjects\Z"""))
    }

    @Test
    fun `a root outside the home and a relocated Claude home round trip`() {
        val source = PathAnonymizer(SourceIdentity(home = """C:\Users\alice""", claudeHome = """D:\claude-data""", roots = listOf("""D:\work\demo""")))
        val target = PathDeanonymizer(root = """E:\target""", home = """C:\Users\bob""", claudeHome = """F:\cc""")
        assertEquals("""E:\target\src F:\cc\projects""", roundTrip("""D:\work\demo\src D:\claude-data\projects""", source, target))
    }

    @Test
    fun `a root that is the home round trips to the target`() {
        val source = PathAnonymizer(SourceIdentity(home = """C:\Users\alice""", roots = listOf("""C:\Users\alice""")))
        assertEquals("""C:\Users\bob\work\Y\a.txt""", roundTrip("""C:\Users\alice\a.txt""", source))
    }

    @Test
    fun `a posix source lands on a windows destination with its separators converted`() {
        val linux = PathAnonymizer(SourceIdentity(home = "/home/alice"))
        val toWindows = PathDeanonymizer(root = bobRoot, home = """C:\Users\bob""", claudeHome = """C:\Users\bob\.claude""", sourceOs = PathOs.POSIX)
        assertEquals("""C:\Users\bob\x""", roundTrip("/home/alice/x", linux, toWindows))
        assertEquals("""cat C:\Users\bob\a\b.txt now""", roundTrip("cat /home/alice/a/b.txt now", linux, toWindows))
        // Without knowing the source is POSIX a forward-slash path is kept as written.
        assertEquals("C:/Users/bob/x", roundTrip("/home/alice/x", linux, bob))
    }

    @Test
    fun `a windows source lands on a posix destination with its separators converted`() {
        val mac = PathDeanonymizer(root = "/Users/bob/proj", home = "/Users/bob", claudeHome = "/Users/bob/.claude")
        assertEquals("/Users/bob/x", roundTrip("""C:\Users\alice\x""", to = mac))
        assertEquals("/Users/bob/x/y.txt", roundTrip("""C:\\Users\\alice\\x\\y.txt""", to = mac))
        assertEquals("/Users/bob/x and /Users/bob/y.", roundTrip("""C:\Users\alice\x and C:\Users\alice\y.""", to = mac))
        assertEquals("/Users/bob/x", roundTrip("C:/Users/alice/x", to = mac))
        assertEquals("/Users/bob/proj/src", roundTrip("""C:\Users\alice\IdeaProjects\X\src""", to = mac))
    }

    @Test
    fun `on windows both ways the tail keeps its separators`() {
        assertEquals("""C:\Users\bob\a/b\c""", roundTrip("""C:\Users\alice\a/b\c"""))
    }

    // ---------------------------------------------------------------------------------- collision --

    @Test
    fun `a literal token of the source survives the round trip under a nonce prefix`() {
        val line = gson.toJson(mapOf("text" to "docs say {{SP:HOME:win}} and {{SP:ROOT:enc}}", "cwd" to aliceRoot, "p" to """C:\Users\alice\n.txt"""))
        val prefix = PathTokens.choosePrefix { line.contains(it) }
        assertTrue(prefix, prefix.length == 6)

        val anonymized = PathAnonymizer(alice, prefix).anonymizeJsonLine(line)
        assertTrue(anonymized.contains("{{$prefix:ROOT:win}}"))
        assertTrue(anonymized.contains("docs say {{SP:HOME:win}} and {{SP:ROOT:enc}}"))
        assertFalse(anonymized.contains("alice"))

        val restored = PathDeanonymizer(bobRoot, """C:\Users\bob""", """C:\Users\bob\.claude""", tokenPrefix = prefix).deanonymizeJsonLine(anonymized)
        val json = JsonParser.parseString(restored).asJsonObject
        assertEquals("docs say {{SP:HOME:win}} and {{SP:ROOT:enc}}", json.get("text").asString)
        assertEquals(bobRoot, json.get("cwd").asString)
        assertEquals("""C:\Users\bob\n.txt""", json.get("p").asString)
        assertEquals(0, PathTokens.count(restored, prefix))
    }

    // -------------------------------------------------------------------------------- json lines --

    @Test
    fun `json documents stored in strings are rendered at their own escaping level`() {
        val inner = gson.toJson(mapOf("cwd" to """C:\Users\alice\p"""))
        val outer = gson.toJson(mapOf("content" to inner))
        val twice = gson.toJson(mapOf("content" to gson.toJson(mapOf("content" to inner))))

        val once = bob.deanonymizeJsonLine(anonymizer.anonymizeJsonLine(outer))
        assertEquals(inner.replace("alice", "bob"), JsonParser.parseString(once).asJsonObject.get("content").asString)
        assertEquals(outer.replace("alice", "bob"), once)

        val deep = bob.deanonymizeJsonLine(anonymizer.anonymizeJsonLine(twice))
        assertEquals(twice.replace("alice", "bob"), deep)
        assertEquals(
            """{"content":"{\"cwd\":\"C:\\\\Users\\\\bob\\\\p\"}"}""",
            JsonParser.parseString(deep).asJsonObject.get("content").asString,
        )
    }

    @Test
    fun `keys and values of a whole transcript round trip with the line endings kept`() {
        val snapshot = gson.toJson(
            mapOf(
                "snapshot" to mapOf(
                    "trackedFileBackups" to mapOf("""$aliceRoot\src\Main.kt""" to mapOf("realParentDir" to """$aliceRoot\src""")),
                ),
            ),
        )
        val plain = gson.toJson(mapOf("keep" to "me"))
        val text = "$snapshot\r\n$plain\r\n\r\n$snapshot"

        val anonymized = anonymizer.anonymizeJsonl(text)
        assertFalse(anonymized.contains("alice"))
        assertEquals(PathTokens.count(anonymized), 4)

        val restored = bob.deanonymizeJsonl(anonymized)
        val expectedLine = gson.toJson(
            mapOf(
                "snapshot" to mapOf(
                    "trackedFileBackups" to mapOf("""$bobRoot\src\Main.kt""" to mapOf("realParentDir" to """$bobRoot\src""")),
                ),
            ),
        )
        assertEquals("$expectedLine\r\n$plain\r\n\r\n$expectedLine", restored)
    }

    @Test
    fun `a line without a token comes back as the same string`() {
        val line = """{ "a" : "caf\u00e9" }"""
        assertSame(line, bob.deanonymizeJsonLine(line))
        assertSame(line, bob.deanonymizeJsonl(line))
        val literal = """{"t":"{{SP7f3a:HOME:win}}"}"""
        assertSame(literal, bob.deanonymizeJsonLine(literal))
    }

    @Test
    fun `an unparsable line is rendered as raw text at the raw level`() {
        val partial = """{"cwd":"C:\\Users\\alice\\IdeaProjects\\X","text":"C:\\Users\\alice\\n"""
        val restored = bob.deanonymizeJsonLine(anonymizer.anonymizeJsonLine(partial))
        assertEquals("""{"cwd":"C:\\Users\\bob\\work\\Y","text":"C:\\Users\\bob\\n""", restored)
    }

    // ------------------------------------------------------------------------------ source identity --

    @Test
    fun `an identity normalizes its values and drops a Claude home below the home`() {
        val identity = SourceIdentity(home = "C:/Users/alice/", claudeHome = """c:\users\ALICE\.claude""", roots = listOf("D:/work/demo/", "D:\\work\\demo"))
        assertEquals("""C:\Users\alice""", identity.home)
        assertNull(identity.claudeHome)
        assertEquals(listOf("""D:\work\demo"""), identity.roots)
        assertEquals("alice", identity.userName)
        assertEquals("ALICE", identity.shortStem)
        assertEquals(PathOs.WINDOWS, identity.os)
        assertEquals(PathOs.POSIX, SourceIdentity(home = "/home/alice").os)
        assertEquals("ZOMLLE", SourceIdentity(home = "C:\\Users\\Zo\u00eb M\u00fcller").shortStem)
    }

    @Test
    fun `detect reads the home and finds the short form of an injected temp folder`() {
        val identity = SourceIdentity.detect(
            claudeHome = Paths.get("""C:\Users\alice\.claude"""),
            roots = listOf("""C:\Users\alice\p"""),
            home = """C:\Users\alice""",
            tempFolders = listOf("""C:\Users\ALICE~1\AppData\Local\Temp""", """C:\Windows\Temp"""),
            realPathOf = { raw -> if (raw.startsWith("""C:\Users""")) """C:\Users\alice\AppData\Local\Temp""" else raw },
        )
        assertEquals(listOf("""C:\Users\ALICE~1"""), identity.shortHomes)
        assertEquals(listOf("""C:\Users\alice\p"""), identity.roots)
        assertNull(identity.claudeHome)
        assertEquals(listOf("""D:\r"""), identity.withRoots(listOf("""D:\r""")).roots)
    }

    @Test
    fun `detect on the running machine names the real home without reading it back as a fixture`() {
        val identity = SourceIdentity.detect(claudeHome = Paths.get(System.getProperty("user.home"), ".claude"))
        assertEquals(ClaudePaths.normalizeProjectPath(System.getProperty("user.home")), identity.home)
        assertNull(identity.claudeHome)
    }
}
