package com.limelight.utils

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CacheHelperTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cache-helper-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun normalNestedCachePathCanBeWrittenAndRead() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        CacheHelper.openCacheFileForOutput(root, "boxart", uuid, "123.png").use {
            it.write(byteArrayOf(1, 2, 3))
        }

        val file = CacheHelper.openPath(false, root, "boxart", uuid, "123.png")
        assertEquals(3L, file.length())
        assertTrue(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
    }

    @Test
    fun unsafeComponentsAreRejectedBeforeDirectoriesAreCreated() {
        val unsafeComponents = listOf(
            "",
            ".",
            "..",
            "../outside",
            "..\\outside",
            "nested/file",
            "nested\\file",
            root.resolve("absolute").absolutePath,
            "bad\u0000name"
        )

        for (component in unsafeComponents) {
            assertThrows(IllegalArgumentException::class.java) {
                CacheHelper.openPath(true, root, "pending", component, "file")
            }
        }
        assertFalse(root.resolve("pending").exists())
    }

    @Test
    fun rejectedDeleteCannotAffectParentOrSiblingFiles() {
        val marker = root.resolve("marker.txt").apply { writeText("keep") }

        assertThrows(IllegalArgumentException::class.java) {
            CacheHelper.deleteCacheFile(root, "applist", "..", marker.name)
        }

        assertTrue(marker.exists())
        assertEquals("keep", marker.readText())
    }

    @Test
    fun similarDirectoryPrefixIsNotInsideRoot() {
        val sibling = File(root.parentFile, root.name + "-other").canonicalFile
        val candidate = File(sibling, "cache.bin").canonicalFile

        assertFalse(CacheHelper.isPathWithinRoot(root.canonicalFile, candidate))
        assertTrue(
            CacheHelper.isPathWithinRoot(
                root.canonicalFile,
                File(root, "cache.bin").canonicalFile
            )
        )
    }
}
