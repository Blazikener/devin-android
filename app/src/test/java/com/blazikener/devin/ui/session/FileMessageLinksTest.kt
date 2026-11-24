package com.blazikener.devin.ui.session

import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.data.SessionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileMessageLinksTest {
    private val file = SessionAttachment("report", "report.md", "https://example.com/report.md")

    @Test fun laterFilenameMentionDoesNotStealLinkedFileCard() {
        val messages = listOf(
            SessionMessage("shared", "devin", "[Report](${file.url})"),
            SessionMessage("reply", "user", "Please revise report.md"),
            SessionMessage("repeat", "devin", "Same file: ${file.url}"),
        )
        assertEquals("shared", file.firstLinkedMessageId(messages))
    }

    @Test fun filenameAloneDoesNotInventFileOwnership() {
        assertNull(file.firstLinkedMessageId(listOf(SessionMessage("mention", "user", "Where is report.md?"))))
    }
}
