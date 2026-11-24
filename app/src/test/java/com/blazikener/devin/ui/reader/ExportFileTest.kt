package com.blazikener.devin.ui.reader

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileTest {
    @Test fun repeatedNamesCannotOverwriteOrDeleteAnEarlierHandoff() {
        val root = Files.createTempDirectory("reader-export").toFile()
        try {
            val first = newExportFile(root, "report.pdf").apply { writeText("saved report") }
            val second = newExportFile(root, "report.pdf")
            second.delete() // A rejected request cleans up only its own candidate.
            second.parentFile?.delete()
            assertNotEquals(first, second)
            assertEquals("saved report", first.readText())
            assertEquals("report.pdf", first.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun untrustedBasenamesStayInsideTheAttemptDirectory() {
        val root = Files.createTempDirectory("reader-export").toFile()
        try {
            listOf("..", ".", "../../..", "../../report.pdf", "").forEach { name ->
                val candidate = newExportFile(root, name)
                assertEquals(candidate.parentFile.canonicalFile, candidate.canonicalFile.parentFile)
                assertTrue(candidate.canonicalPath.startsWith(root.canonicalPath + "/exports/"))
            }
        } finally { root.deleteRecursively() }
    }
}
