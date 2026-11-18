package com.blazikener.devin.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.graph
import java.util.concurrent.TimeUnit

/**
 * Background check that turns session state changes into notifications, so you don't
 * have to keep a browser tab open to know when Devin needs you.
 */
class SessionWatcher(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val account = g.auth.account.value ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("watcher", Context.MODE_PRIVATE)
        val sessions = runCatching {
            g.api.listSessions(first = 50, archived = false, userIds = account.userId?.let { listOf(it) }).items
        }.getOrElse { return Result.retry() }
        check(applicationContext, sessions, prefs, g.settings.value.let { Triple(it.notifyNeedsYou, it.notifyFinished, it.notifyMessages) })
        return Result.success()
    }

    companion object {
        private const val NAME = "session-watcher"

        fun schedule(ctx: Context, minutes: Int) {
            val req = PeriodicWorkRequestBuilder<SessionWatcher>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)

        /** Also called from the app while it's open so state is shared and nothing double-notifies. */
        suspend fun check(
            ctx: Context,
            sessions: List<Session>,
            prefs: android.content.SharedPreferences,
            flags: Triple<Boolean, Boolean, Boolean>,
            suppressId: String? = null,
        ) {
            val g = ctx.graph
            val (needsYou, finished, messages) = flags
            val firstRun = !prefs.getBoolean("baseline", false)
            val edit = prefs.edit()
            for (s in sessions) {
                val key = "s_${s.id}"
                val prev = prefs.getString(key, null)
                val now = "${s.state.name}|${s.updatedAt}"
                edit.putString(key, now)
                if (firstRun || prev == now || s.id == suppressId) continue
                val prevState = prev?.substringBefore('|')
                val changedState = prevState != s.state.name
                val lastDevin = if (messages || (changedState && s.state.needsAttention)) latestDevinMessage(ctx, s.id) else null
                val lastSeen = prefs.getString("m_${s.id}", null)
                if (lastDevin != null) edit.putString("m_${s.id}", lastDevin.first)
                val newMessage = lastDevin != null && lastDevin.first != lastSeen
                when {
                    changedState && s.state.needsAttention && needsYou -> Notifications.show(
                        ctx, Notifications.CH_NEEDS_YOU, s,
                        if (s.state == SessionState.Blocked) "Devin is blocked" else "Devin needs you",
                        lastDevin?.second ?: s.displayTitle, allowReply = s.state != SessionState.Blocked,
                    )
                    changedState && s.state == SessionState.Finished && finished -> Notifications.show(
                        ctx, Notifications.CH_FINISHED, s, "Session finished",
                        lastDevin?.second ?: s.displayTitle, allowReply = true,
                    )
                    changedState && s.state == SessionState.Error && finished -> Notifications.show(
                        ctx, Notifications.CH_FINISHED, s, "Session hit an error", s.displayTitle, allowReply = false,
                    )
                    newMessage && messages && prev != null -> Notifications.show(
                        ctx, Notifications.CH_MESSAGES, s, "New message from Devin",
                        lastDevin!!.second, allowReply = true,
                    )
                }
            }
            edit.putBoolean("baseline", true).apply()
            g.settings.value // touch to keep settings warm
        }

        /** Records a state the user caused themselves (sleep, terminate) so it never alerts them. */
        fun markSeen(ctx: Context, s: Session) {
            ctx.getSharedPreferences("watcher", Context.MODE_PRIVATE).edit()
                .putString("s_${s.id}", "${s.state.name}|${s.updatedAt}").apply()
        }

        private suspend fun latestDevinMessage(ctx: Context, id: String): Pair<String, String>? {
            val g = ctx.graph
            val prefs = ctx.getSharedPreferences("watcher", Context.MODE_PRIVATE)
            var cursor = prefs.getString("c_$id", null)
            var latest: Pair<String, String>? = null
            runCatching {
                repeat(10) {
                    val page = g.api.messages(id, after = cursor)
                    page.items.lastOrNull { it.fromDevin }?.let { latest = it.eventId to it.message }
                    if (page.endCursor != null) cursor = page.endCursor
                    if (!page.hasNextPage) return@runCatching
                }
            }
            prefs.edit().putString("c_$id", cursor).apply()
            return latest
        }
    }
}
