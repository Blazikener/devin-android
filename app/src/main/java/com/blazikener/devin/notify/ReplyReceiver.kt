package com.blazikener.devin.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.blazikener.devin.data.SendMessageRequest
import com.blazikener.devin.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Sends an inline notification reply straight to the session, no app launch needed. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifications.EXTRA_SESSION_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val pending = goAsync()
        val g = context.graph
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = runCatching {
                g.api.sendMessage(id, SendMessageRequest(text, messageAsUserId = g.settings.value.createAsUserId))
            }.isSuccess
            if (!ok) g.settings.saveDraft(id, text)
            Notifications.showSent(context, id, text, ok)
            pending.finish()
        }
    }
}
