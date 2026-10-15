package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class PathInputTest {

    @Test
    fun `on Windows a Git Bash drive becomes a Windows one`() {
        assertEquals("C:\\Users\\alice\\work", PathInput.normalize("/c/Users/alice/work", isWindows = true))
        assertEquals("D:\\x", PathInput.normalize("/d/x/", isWindows = true))
        assertEquals("C:\\", PathInput.normalize("/c", isWindows = true))
        assertEquals("C:\\", PathInput.normalize("/c/", isWindows = true))
    }

    @Test
    fun `on Windows WSL and Cygwin mounts become Windows drives too`() {
        assertEquals("C:\\Users\\alice", PathInput.normalize("/mnt/c/Users/alice", isWindows = true))
        assertEquals("X:\\", PathInput.normalize("/mnt/x/", isWindows = true))
        assertEquals("E:\\data", PathInput.normalize("/cygdrive/e/data", isWindows = true))
    }

    @Test
    fun `a Windows path is only normalized`() {
        assertEquals("C:\\Users\\alice\\work", PathInput.normalize("C:/Users/alice/work/", isWindows = true))
        assertEquals("C:\\Users\\alice\\work", PathInput.normalize("C:\\Users\\alice\\work\\", isWindows = true))
    }

    @Test
    fun `paths that are not a drive are left alone`() {
        assertEquals("/tmp/x", PathInput.normalize("/tmp/x", isWindows = true))
        assertEquals("/home/alice", PathInput.normalize("/home/alice", isWindows = true))
        assertEquals("/ab/x", PathInput.normalize("/ab/x", isWindows = true))
    }

    @Test
    fun `on other systems nothing is converted`() {
        assertEquals("/c/Users/alice", PathInput.normalize("/c/Users/alice", isWindows = false))
        assertEquals("/mnt/c/x", PathInput.normalize("/mnt/c/x", isWindows = false))
        assertEquals("/home/alice/work", PathInput.normalize("/home/alice/work/", isWindows = false))
    }

    @Test
    fun `a relative path is taken from the working directory`() {
        val workingDirectory = Paths.get("").toAbsolutePath()
        assertEquals(workingDirectory.toString(), PathInput.normalize(".", isWindows = false, workingDirectory = workingDirectory.toString()))
        assertEquals(workingDirectory.resolve("work").toString(), PathInput.normalize("work/", isWindows = false, workingDirectory = workingDirectory.toString()))
        assertEquals(workingDirectory.resolve("work").toString(), PathInput.normalize("./sub/../work", isWindows = false, workingDirectory = workingDirectory.toString()))
        // An absolute path, in either style, is not touched, and neither is any path when there is no working directory to take it from.
        assertEquals("/home/alice/work", PathInput.normalize("/home/alice/work", isWindows = false, workingDirectory = "/elsewhere"))
        assertEquals("C:\\Users\\alice\\work", PathInput.normalize("C:\\Users\\alice\\work", isWindows = false, workingDirectory = "/elsewhere"))
        assertEquals("work", PathInput.normalize("work", isWindows = false))
    }

    @Test
    fun `the conversion alone keeps a trailing separator`() {
        assertEquals("C:\\out\\", PathInput.toNativePath("/c/out/", isWindows = true))
    }
}
