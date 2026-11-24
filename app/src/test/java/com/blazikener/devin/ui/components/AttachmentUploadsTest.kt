package com.blazikener.devin.ui.components

import com.blazikener.devin.data.DraftAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentUploadsTest {
    private val ready = AttachmentUpload(1, result = DraftAttachment("report.md", "https://example.test/report.md"))

    @Test fun exposesAllUrlsOnlyWhenEveryAttachmentIsReady() {
        val other = AttachmentUpload(2, result = DraftAttachment("notes.md", "https://example.test/notes.md"))
        assertEquals(listOf(ready.result!!.url, other.result!!.url), listOf(ready, other).readyAttachmentUrls())
        assertEquals(emptyList<String>(), emptyList<AttachmentUpload>().readyAttachmentUrls())
    }

    @Test fun incompleteUploadsPreventSendingEvenWithOtherSuccessfulAttachments() {
        assertNull(listOf(ready, AttachmentUpload(2)).readyAttachmentUrls())
        assertNull(listOf(ready, AttachmentUpload(2, error = "Upload failed")).readyAttachmentUrls())
    }
}
