package io.github.kvmy666.autoexpand

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * Debug-only test-notification sender.
 *
 * Exists so the notification expand engine can be exercised deterministically instead of
 * waiting for a real message to arrive. Every shape that the engine treats differently has
 * its own entry: single vs group summary, messaging vs big-text, heads-up vs shade-only.
 *
 * [Kind.AutoGroupFlood] is the one that answers "did this ROM stop grouping?" — AOSP
 * auto-bundles the 4th+ notification from one app even when the app sets no group key, so
 * posting five singles and watching whether a summary row appears is a direct read on the
 * platform's grouping behaviour.
 *
 * The delay parameter matters more than it looks: lock-screen behaviour can only be tested
 * with a notification that arrives *after* the screen is locked, so the sender has to be
 * able to fire while the app is in the background.
 */
object TestNotifier {

    const val CHANNEL_HIGH = "ae_test_high"
    const val CHANNEL_LOW  = "ae_test_low"

    private const val GROUP_KEY = "io.github.kvmy666.autoexpand.TEST_GROUP"

    /** 1001/1002 belong to the Snapper and Zones foreground services — start well clear. */
    private const val ID_BASE = 2000
    private var nextId = ID_BASE

    private val handler = Handler(Looper.getMainLooper())

    enum class Kind(val label: String, val description: String) {
        BigText(
            "Big text",
            "Single notification with a long collapsible body"
        ),
        Messaging(
            "Messaging style",
            "MessagingStyle, like a chat app — the common real-world case"
        ),
        Inbox(
            "Inbox style",
            "InboxStyle with five lines"
        ),
        LongText(
            "Very long text",
            "Checks the heads-up max-lines clamp"
        ),
        Group(
            "Group (summary + 2 children)",
            "Explicit group key — the legacy grouped-notification path"
        ),
        AutoGroupFlood(
            "5 singles (auto-group test)",
            "Five ungrouped notifications. If the ROM still auto-bundles, a summary row appears"
        ),
        Silent(
            "Silent (shade only)",
            "Low importance — lands in the shade with no heads-up banner"
        ),
    }

    /**
     * Posts [kind] after [delayMs]. Runs on the main looper, so it keeps firing while the
     * activity is merely paused (screen locked) — which is exactly the lock-screen test case.
     */
    fun post(context: Context, kind: Kind, delayMs: Long) {
        val app = context.applicationContext
        if (delayMs <= 0L) postNow(app, kind) else handler.postDelayed({ postNow(app, kind) }, delayMs)
    }

    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (id in ID_BASE until nextId) {
            try { nm.cancel(id) } catch (_: Throwable) {}
        }
        nextId = ID_BASE
    }

    private fun postNow(context: Context, kind: Kind) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            when (kind) {
                Kind.BigText        -> nm.notify(nextId(), bigText(context))
                Kind.Messaging      -> nm.notify(nextId(), messaging(context))
                Kind.Inbox          -> nm.notify(nextId(), inbox(context))
                Kind.LongText       -> nm.notify(nextId(), longText(context))
                Kind.Silent         -> nm.notify(nextId(), silent(context))
                Kind.Group          -> postGroup(nm, context)
                Kind.AutoGroupFlood -> repeat(5) { i -> nm.notify(nextId(), flood(context, i + 1)) }
            }
        } catch (_: Throwable) {
            // Most likely POST_NOTIFICATIONS was denied. The screen surfaces that separately.
        }
    }

    private fun nextId(): Int = nextId++

    private fun base(context: Context, channel: String = CHANNEL_HIGH) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)

    private fun bigText(context: Context) = base(context)
        .setContentTitle("Big text test")
        .setContentText("Collapsed line — tap the arrow to expand")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "Expanded body line 1.\nExpanded body line 2.\nExpanded body line 3.\n" +
                "If auto-expand is working you are reading this without touching anything."
            )
        )
        .build()

    private fun messaging(context: Context): android.app.Notification {
        val me    = Person.Builder().setName("You").build()
        val other = Person.Builder().setName("Test Contact").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Test Contact")
            .addMessage("First message", System.currentTimeMillis() - 60_000, other)
            .addMessage("Second message, a bit longer than the first", System.currentTimeMillis() - 30_000, other)
            .addMessage("Third message so the expanded view has something to show", System.currentTimeMillis(), other)
        return base(context)
            .setContentTitle("Test Contact")
            .setContentText("Third message so the expanded view has something to show")
            .setStyle(style)
            .build()
    }

    private fun inbox(context: Context) = base(context)
        .setContentTitle("Inbox test")
        .setContentText("5 new items")
        .setStyle(
            NotificationCompat.InboxStyle()
                .addLine("Line one")
                .addLine("Line two")
                .addLine("Line three")
                .addLine("Line four")
                .addLine("Line five")
        )
        .build()

    private fun longText(context: Context) = base(context)
        .setContentTitle("Long text test")
        .setContentText("Checks the heads-up max-lines clamp")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                (1..14).joinToString("\n") { "Line $it of a deliberately long notification body." }
            )
        )
        .build()

    private fun silent(context: Context) = base(context, CHANNEL_LOW)
        .setContentTitle("Silent test")
        .setContentText("Shade only — no heads-up banner")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "This one goes straight to the shade so shade expansion can be tested " +
                "without a banner appearing first."
            )
        )
        .build()

    /** Children first, then the summary — the order a real app posts in. */
    private fun postGroup(nm: NotificationManager, context: Context) {
        nm.notify(nextId(), base(context)
            .setContentTitle("Group child 1")
            .setContentText("First child message")
            .setStyle(NotificationCompat.BigTextStyle().bigText("First child, expanded body text."))
            .setGroup(GROUP_KEY)
            .build())
        nm.notify(nextId(), base(context)
            .setContentTitle("Group child 2")
            .setContentText("Second child message")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Second child, expanded body text."))
            .setGroup(GROUP_KEY)
            .build())
        nm.notify(nextId(), base(context)
            .setContentTitle("Group summary")
            .setContentText("2 messages")
            .setStyle(
                NotificationCompat.InboxStyle()
                    .addLine("Group child 1")
                    .addLine("Group child 2")
                    .setSummaryText("2 messages")
            )
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .build())
    }

    private fun flood(context: Context, index: Int) = base(context)
        .setContentTitle("Ungrouped #$index")
        .setContentText("No group key set")
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText("Ungrouped notification $index of 5, with an expanded body so the " +
                         "expand state is visible at a glance.")
        )
        .build()
}
