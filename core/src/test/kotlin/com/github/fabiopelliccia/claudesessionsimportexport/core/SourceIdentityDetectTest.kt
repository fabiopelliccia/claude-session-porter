package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * `SourceIdentity.detect` end to end (WP4/WP5 review, finding 3): the identity an export builds from where
 * its Claude home is, with what the anonymizer does with it. Nothing here reads the real `user.home`.
 */
class SourceIdentityDetectTest {

    private fun detect(claudeHome: String, home: String, accountName: String?, roots: List<String> = emptyList()): SourceIdentity =
        SourceIdentity.detect(
            claudeHome = Paths.get(claudeHome),
            roots = roots,
            home = home,
            tempFolders = emptyList(),
            accountName = accountName,
        )

    private fun anonymize(identity: SourceIdentity, text: String): Pair<String, PathAnonymizer.Stats> {
        val stats = PathAnonymizer.Stats()
        return PathAnonymizer(identity).anonymizeText(text, stats) to stats
    }

    @Test
    fun `an account exporting the Claude home of another one anonymizes both homes and counts both names`() {
        // The reviewer's scenario: `--home C:\Users\alice\.claude`, run by the service account svc-build.
        val identity = detect("""C:\Users\alice\.claude""", home = """C:\Users\svc-build""", accountName = "svc-build")

        assertEquals("""C:\Users\alice""", identity.home)
        assertEquals(listOf("""C:\Users\svc-build"""), identity.otherHomes)
        assertEquals("""C:\Users\alice\.claude""", identity.claudeHome)
        assertEquals(listOf("alice", "svc-build"), identity.allUserNames)

        assertEquals("""{{SP:HOME:win}}\notes.txt""", anonymize(identity, """C:\Users\alice\notes.txt""").first)
        assertEquals("""{{SP:HOME:win}}\.m2""", anonymize(identity, """C:\Users\svc-build\.m2""").first)
        assertEquals("""{{SP:CLAUDE_HOME:win}}\projects\x""", anonymize(identity, """C:\Users\alice\.claude\projects\x""").first)
        // The bare names, outside any path, are what the residual check reports.
        assertEquals(2, anonymize(identity, "ask alice, the build runs as svc-build").second.residualUserNameHits)
        // The 8.3 spelling of either home.
        assertEquals("""{{SP:HOME:short}}\AppData""", anonymize(identity, """C:\Users\ALICE~1\AppData""").first)
        assertEquals("""{{SP:HOME:short}}\AppData""", anonymize(identity, """C:\Users\SVC-BU~1\AppData""").first)
    }

    @Test
    fun `a Claude home in WSL is read as the POSIX home it is, and the Windows home still counts`() {
        val identity = detect("""\\wsl.localhost\Ubuntu\home\alice\.claude""", home = """C:\Users\alice""", accountName = "alice")

        assertEquals("/home/alice", identity.home)
        assertEquals(PathOs.POSIX, identity.os)
        // The Windows home of the account, and the home as the Windows side of WSL spells it.
        assertEquals(
            listOf("""C:\Users\alice""", """\\wsl.localhost\Ubuntu\home\alice""", """\\wsl${'$'}\Ubuntu\home\alice"""),
            identity.otherHomes,
        )
        assertEquals("/home/alice/.claude", identity.claudeHome)

        // What the reviewer saw left behind: the POSIX paths of a session run inside WSL.
        assertEquals("cat {{SP:HOME:fwd}}/.bashrc", anonymize(identity, "cat /home/alice/.bashrc").first)
        assertEquals("{{SP:CLAUDE_HOME:fwd}}/projects/x", anonymize(identity, "/home/alice/.claude/projects/x").first)
        assertEquals("""{{SP:HOME:win}}\notes.txt""", anonymize(identity, """C:\Users\alice\notes.txt""").first)
        // The share spelled the Windows way, with both kinds of server name.
        val share = """\\wsl.localhost\Ubuntu\home\alice\x"""
        assertEquals("""{{SP:HOME:win}}\x""", anonymize(identity, share).first)
        assertEquals("""{{SP:HOME:win}}\x""", anonymize(identity, share.replace("wsl.localhost", "wsl$")).first)
    }

    @Test
    fun `the share of a distribution without anything below it is the root, and a path that is no share is left alone`() {
        assertEquals("/", SourceIdentity.parseWslShare("""\\wsl.localhost\Ubuntu""")?.posixPath)
        val debian = SourceIdentity.parseWslShare("//wsl\$/Debian/home/alice/.claude")!!
        assertEquals("/home/alice/.claude", debian.posixPath)
        assertEquals("Debian", debian.distribution)
        assertEquals(listOf("""\\wsl.localhost\Debian\home\alice""", """\\wsl${'$'}\Debian\home\alice"""), debian.windowsSpellingsOf("/home/alice"))
        assertEquals(null, SourceIdentity.parseWslShare("""\\server\share\home"""))
        assertEquals(null, SourceIdentity.parseWslShare("""C:\Users\alice\.claude"""))
    }

    @Test
    fun `the Claude home of the running account adds nothing but the Claude home itself`() {
        val identity = detect("""C:\Users\alice\.claude""", home = """C:\Users\alice""", accountName = "alice")

        assertEquals("""C:\Users\alice""", identity.home)
        assertEquals(emptyList<String>(), identity.otherHomes)
        assertEquals(listOf("alice"), identity.allUserNames)
        // Paths into the Claude home are the Claude home's, not the home's (finding 9).
        assertEquals("""{{SP:CLAUDE_HOME:win}}\projects\x""", anonymize(identity, """C:\Users\alice\.claude\projects\x""").first)
    }

    @Test
    fun `a Claude home that is relocated keeps the running account's home and is an anchor of its own`() {
        val identity = detect("""D:\claude-data""", home = """C:\Users\alice""", accountName = "alice")

        assertEquals("""C:\Users\alice""", identity.home)
        assertEquals(emptyList<String>(), identity.otherHomes)
        assertEquals("""{{SP:CLAUDE_HOME:win}}\projects""", anonymize(identity, """D:\claude-data\projects""").first)
    }

    @Test
    fun `a folder called dot claude inside a folder that is no home does not turn that folder into a home`() {
        // D:\tools has one folder name: it is not where a person lives, and every project below it would turn into HOME.
        val identity = detect("""D:\tools\.claude""", home = """C:\Users\alice""", accountName = "alice")

        assertEquals("""C:\Users\alice""", identity.home)
        assertEquals(emptyList<String>(), identity.otherHomes)
        assertEquals("""D:\tools\proj\x""", anonymize(identity, """D:\tools\proj\x""").first)
    }

    @Test
    fun `the OS account name counts as a name even when it is not the leaf of the home`() {
        // A profile folder that does not carry the account name (a redirected or renamed one).
        val identity = detect("""C:\Users\a1234\.claude""", home = """C:\Users\a1234""", accountName = "Alice")

        assertEquals(listOf("a1234", "Alice"), identity.allUserNames)
        assertEquals(1, anonymize(identity, "mail Alice about it").second.residualUserNameHits)
        assertEquals(1, anonymize(identity, "mail alice about it").second.residualUserNameHits)
        assertEquals(0, anonymize(identity, "alice2 and alicette are others").second.residualUserNameHits)
    }

    @Test
    fun `an identity built with explicit values names every home and name the same way`() {
        val identity = SourceIdentity(
            home = """C:\Users\alice""", otherHomes = listOf("C:/Users/alice/", """C:\Users\bob"""), otherNames = listOf(" carol ", "carol", ""),
        )
        assertEquals(listOf("""C:\Users\bob"""), identity.otherHomes) // the same home written another way is not another home
        assertEquals(listOf("carol"), identity.otherNames)
        assertEquals(listOf("alice", "bob", "carol"), identity.allUserNames)
        assertTrue(identity.isUserName("BOB"))
        assertEquals(listOf("""C:\Users\alice""", """C:\Users\bob"""), identity.allHomes)
        // withRoots keeps all of it.
        val withRoot = identity.withRoots(listOf("""D:\w\p"""))
        assertEquals(identity.otherHomes, withRoot.otherHomes)
        assertEquals(identity.otherNames, withRoot.otherNames)
    }
}
