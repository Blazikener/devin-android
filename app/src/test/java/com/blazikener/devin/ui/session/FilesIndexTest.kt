package com.blazikener.devin.ui.session

import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.SessionAttachment
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FilesIndexTest {
    private val plan = SessionAttachment("plan", "report.md", "https://example.com/report.md")
    private val pdf = SessionAttachment("pdf", "report.pdf", "https://example.com/report.pdf")

    @Test fun loadingAndFailedRequestsNeverClaimAnEmptyIndex() {
        val initial = FilesIndex()
        assertNull(initial.knownCount)
        assertFalse(initial.confirmedEmpty)
        val failed = initial.failed(ApiException(500, "Server unavailable"))
        assertNull(failed.knownCount)
        assertFalse(failed.confirmedEmpty)
        assertFalse(failed.offline)
        assertFalse(failed.refreshing)
    }

    @Test fun emptyIsOnlyConfirmedAfterSuccessfulResponse() {
        val savedEmpty = FilesIndex(saved = emptyList(), fromCache = true)
        assertFalse(savedEmpty.confirmedEmpty)
        assertFalse(savedEmpty.failed(IOException()).confirmedEmpty)
        val loaded = savedEmpty.loaded(emptyList())
        assertTrue(loaded.confirmedEmpty)
        assertEquals(0, loaded.knownCount)
        assertFalse(loaded.loading().confirmedEmpty)
        assertFalse(loaded.failed(ApiException(403, "Not allowed")).confirmedEmpty)
    }

    @Test fun refreshFailureRetainsTheExactSavedRowsAndKnownCount() {
        val loaded = FilesIndex().loaded(listOf(plan, pdf))
        val failed = loaded.loading().failed(IOException("offline"))
        assertEquals(listOf(plan, pdf), failed.rows)
        assertEquals(2, failed.knownCount)
        assertTrue(failed.offline)
        assertFalse(failed.confirmedEmpty)
        assertEquals(listOf(pdf), failed.loaded(listOf(pdf)).rows)
    }

    @Test fun offlineWithNoSavedIndexKeepsUnknownCount() {
        val failed = FilesIndex().failed(IOException())
        assertTrue(failed.offline)
        assertNull(failed.saved)
        assertNull(failed.knownCount)
        assertTrue(failed.rows.isEmpty())
        assertFalse(failed.confirmedEmpty)
    }

    @Test fun successfulIndexDeduplicatesByStableIdentity() {
        val index = FilesIndex().loaded(listOf(plan, plan, pdf))
        assertEquals(2, index.knownCount)
        assertEquals(listOf(plan, pdf), index.rows)
    }
}
