package ai.antigem.data.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.antigem.MainActivity
import ai.antigem.R
import ai.antigem.i18n.L
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Asks the user on screen before an agent, Jarvis or a mini app does something only the user should decide (looser
 * approval modes, the AI reply language…). [ask] waits for a tap on [ai.antigem.ui.components.UserConfirmHost] (shown
 * over the app and over mini apps); nothing answered in time counts as "no". When no AntiGem screen is in front, a
 * notification opens the app, where the question waits.
 */
object UserConfirm {
    class Request internal constructor(
        val id: Long,
        val title: String,
        val message: String,
        val confirmLabel: String,
        internal val answer: CompletableDeferred<Boolean>
    )

    private const val CHANNEL = "user_confirm"
    private const val NOTIF_ID = 7731
    private const val TIMEOUT_MS = 120_000L

    private val ids = AtomicLong(0)
    private val shownHosts = AtomicInteger(0)
    private val queue = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = queue.asStateFlow()

    /** True only after the user tapped [confirmLabel]; false for "Don't allow", dismissing, or no answer in time. */
    suspend fun ask(context: Context, title: String, message: String, confirmLabel: String = L.common.allow): Boolean {
        val r = Request(ids.incrementAndGet(), title, message, confirmLabel, CompletableDeferred())
        synchronized(queue) { queue.value = queue.value + r }
        if (shownHosts.get() == 0) notifyWaiting(context.applicationContext, title)
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { r.answer.await() } ?: false
        } finally {
            r.answer.complete(false)
            val left = synchronized(queue) { queue.value = queue.value.filter { it.id != r.id }; queue.value }
            if (left.isEmpty()) runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
        }
    }

    fun answer(r: Request, ok: Boolean) {
        r.answer.complete(ok)
        synchronized(queue) { queue.value = queue.value.filter { it.id != r.id } }
    }

    /** A host on a screen in front started (true) or stopped (false) showing the questions. */
    fun hostShown(shown: Boolean) {
        if (shown) shownHosts.incrementAndGet() else shownHosts.updateAndGet { maxOf(0, it - 1) }
    }

    private fun notifyWaiting(context: Context, title: String) = runCatching {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return@runCatching
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = context.getSystemService(NotificationManager::class.java)
            if (sys.getNotificationChannel(CHANNEL) == null) {
                sys.createNotificationChannel(
                    NotificationChannel(CHANNEL, L.security.userConfirm.confirmations, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = L.security.userConfirm.questionsThatNeedYourOkBefore
                    }
                )
            }
        }
        val open = PendingIntent.getActivity(
            context, NOTIF_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(L.security.userConfirm.antigemNeedsYourOk)
            .setContentText(L.security.userConfirm.openAntigemToAnswerTitle(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
