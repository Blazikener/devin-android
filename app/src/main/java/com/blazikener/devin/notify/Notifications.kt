package com.blazikener.devin.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.blazikener.devin.R
import com.blazikener.devin.data.Session
import com.blazikener.devin.MainActivity

object Notifications {
    const val CH_NEEDS_YOU = "needs_you"
    const val CH_FINISHED = "finished"
    const val CH_MESSAGES = "messages"
    const val KEY_REPLY = "reply_text"
    const val EXTRA_SESSION_ID = "session_id"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_NEEDS_YOU, "Devin needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Devin is blocked on your reply or approval"
                },
                NotificationChannel(CH_MESSAGES, "New messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Devin posted a new message in a session"
                },
                NotificationChannel(CH_FINISHED, "Sessions finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A session finished or stopped"
                },
            )
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(sessionId: String) = sessionId.hashCode()

    fun openIntent(ctx: Context, sessionId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, notificationId(sessionId), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(ctx: Context, channel: String, session: Session, title: String, body: String, allowReply: Boolean) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_devin)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(session.displayTitle)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(ctx, session.id))
            .setPriority(if (channel == CH_NEEDS_YOU) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (allowReply) {
            val replyIntent = Intent(ctx, ReplyReceiver::class.java).putExtra(EXTRA_SESSION_ID, session.id)
            val pi = PendingIntent.getBroadcast(
                ctx, notificationId(session.id), replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Devin").build()
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_devin, "Reply", pi)
                    .addRemoteInput(input).setAllowGeneratedReplies(true).build()
            )
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(notificationId(session.id), b.build()) }
    }

    fun showSent(ctx: Context, sessionId: String, text: String, ok: Boolean) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_devin)
            .setContentTitle(if (ok) "Sent to Devin" else "Couldn't send — tap to retry in the app")
            .setContentText(text)
            .setContentIntent(openIntent(ctx, sessionId))
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 4000 else 0)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(notificationId(sessionId), n) }
    }

    fun cancel(ctx: Context, sessionId: String) = NotificationManagerCompat.from(ctx).cancel(notificationId(sessionId))
}
