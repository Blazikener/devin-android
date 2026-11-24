package com.blazikener.devin.ui.session

import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.data.SessionMessage

// Names are not identities: a later discussion of report.md must not move its card.
internal fun SessionAttachment.firstLinkedMessageId(messages: List<SessionMessage>): String? =
    url.takeIf { it.isNotBlank() }?.let { link ->
        messages.firstOrNull { it.message.contains(link) }?.eventId
    }
