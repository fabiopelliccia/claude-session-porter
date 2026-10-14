package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudePathsTest {

    @Test
    fun `encodes every non-alphanumeric character as a dash`() {
        assertEquals(
            "C--Users-pelliccia-IdeaProjects-Demo",
            ClaudePaths.encodeProjectPath("C:\\Users\\pelliccia\\IdeaProjects\\Demo"),
        )
        assertEquals(
            "-Users-fabio-my-project",
            ClaudePaths.encodeProjectPath("/Users/fabio/my-project"),
        )
    }

    @Test
    fun `letters outside ASCII become a dash, as Claude Code does`() {
        assertEquals("C--Users-fabio-Progetti-caff-", ClaudePaths.encodeProjectPath("C:\\Users\\fabio\\Progetti\\caffè"))
    }

    @Test
    fun `a project path is normalized to the separator of its own style`() {
        // How IntelliJ hands a Windows project over (`project.basePath`) versus how Claude Code records it.
        assertEquals("C:\\Users\\fabio\\Demo", ClaudePaths.normalizeProjectPath("C:/Users/fabio/Demo"))
        assertEquals("C:\\Users\\fabio\\Demo", ClaudePaths.normalizeProjectPath("C:/Users\\fabio/Demo/"))
        assertEquals("/home/fabio/demo", ClaudePaths.normalizeProjectPath("/home/fabio/demo/"))
        // A bare root keeps its separator.
        assertEquals("C:\\", ClaudePaths.normalizeProjectPath("C:/"))
        assertEquals("/", ClaudePaths.normalizeProjectPath("/"))
    }

    @Test
    fun `mapping keeps the remainder below the root and never swallows a sibling`() {
        val mapper = PathMapper("C:\\Users\\fabio\\Demo", "D:/Work/Demo")
        assertEquals("D:\\Work\\Demo", mapper.mapCwd("C:\\Users\\fabio\\Demo"))
        assertEquals("D:\\Work\\Demo\\src", mapper.mapCwd("c:\\users\\FABIO\\demo\\src"))
        assertEquals("D:\\Work\\Demo", mapper.mapCwd("C:\\Users\\fabio\\Demo2"))
        assertEquals(null, mapper.mapFile("C:\\Users\\fabio\\Demo2\\a.kt"))
        assertEquals("D:\\Work\\Demo\\a.kt", mapper.mapFile("C:\\Users\\fabio\\Demo\\a.kt"))

        // Windows to POSIX: the remainder takes the separator of the target.
        assertEquals("/home/bob/demo/src/main", PathMapper("C:\\Users\\fabio\\Demo", "/home/bob/demo").mapCwd("C:\\Users\\fabio\\Demo\\src\\main"))
    }
}
