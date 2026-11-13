package com.sgexpense.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.sgexpense.R
import com.sgexpense.SgExpenseApp
import com.sgexpense.capture.parsers.MissingAmountHint
import com.sgexpense.capture.parsers.UobMissingAmount
import com.sgexpense.data.db.AppDatabase
import com.sgexpense.data.model.CaptureLog
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType
import com.sgexpense.data.repository.ExpenseRepository
import com.sgexpense.ui.quickadd.QuickAddActivity
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import com.sgexpense.util.toLocalDateTime
import kotlinx.coroutines.launch

/** Saves or dismisses UOB alerts that arrived without an amount. */
class AmountPromptService(
    private val context: Context,
    private val db: AppDatabase,
    private val repo: ExpenseRepository,
) {
    data class Pending(val log: CaptureLog, val hint: MissingAmountHint)

    suspend fun pending(logId: Long): Pending? {
        val log = db.captureLog().getById(logId) ?: return null
        if (log.outcome != CaptureProcessor.OUTCOME_NEEDS_AMOUNT) return null
        val hint = UobMissingAmount.hint(log.title, log.text) ?: return null
        return Pending(log, hint)
    }

    /** Creates the transaction. [categoryId] is the user's pick (learned as a rule); null = auto. */
    suspend fun complete(
        logId: Long,
        amountCents: Long,
        merchant: String?,
        type: TxType? = null,
        categoryId: Long? = null,
    ): Transaction? {
        val p = pending(logId) ?: return null
        val name = merchant?.trim().takeUnless { it.isNullOrEmpty() } ?: p.hint.merchant
        val txType = type ?: p.hint.type
        // Keep UOB's payee name when the user typed their own description (e.g. "bbq dinner").
        val payee = p.hint.merchant
        val note = if (payee.isNotBlank() && !name.equals(payee, ignoreCase = true)) {
            "${p.hint.paymentMethod.label} ${if (txType == TxType.EXPENSE) "to" else "from"} $payee"
        } else ""
        val tx = Transaction(
            amountCents = amountCents,
            type = txType,
            merchant = name,
            note = note,
            paymentMethod = p.hint.paymentMethod,
            account = p.hint.account,
            occurredAt = p.log.postedAt,
            source = Source.NOTIFICATION,
            sourceApp = p.log.packageName,
            rawText = listOf(p.log.title, p.log.text).filter { it.isNotBlank() }.joinToString("\n"),
            categoryId = categoryId ?: repo.categorize(name, txType, p.hint.paymentMethod),
        )
        val id = repo.save(tx, previous = if (categoryId == null) tx else null)
        db.captureLog().setOutcome(logId, CaptureProcessor.OUTCOME_COMPLETED, id)
        AmountPromptNotifier.cancel(context, logId)
        return tx.copy(id = id)
    }

    suspend fun dismiss(logId: Long) {
        db.captureLog().setOutcome(logId, CaptureProcessor.OUTCOME_DISMISSED, null)
        AmountPromptNotifier.cancel(context, logId)
    }
}

/** The heads-up notification that asks for the amount. */
object AmountPromptNotifier {
    const val CHANNEL = "amount_prompt"
    const val KEY_REPLY = "amount_reply"
    const val EXTRA_LOG_ID = "log_id"
    const val ACTION_REPLY = "com.sgexpense.AMOUNT_REPLY"
    const val ACTION_DISMISS = "com.sgexpense.AMOUNT_DISMISS"

    private fun notificationId(logId: Long) = 50_000 + (logId % 1_000_000).toInt()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Missing amount prompts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks for the amount when a UOB alert (e.g. PayNow) doesn't include one"
            }
        )
    }

    fun describe(hint: MissingAmountHint, postedAt: Long): String {
        val who = hint.merchant.takeIf { it.isNotBlank() }?.let {
            if (hint.type == TxType.EXPENSE) "to $it" else "from $it"
        }
        val time = postedAt.toLocalDateTime().format(DateParsing.displayTime)
        return listOfNotNull(who, hint.account, time).joinToString(" · ")
    }

    fun show(context: Context, logId: Long, hint: MissingAmountHint, postedAt: Long, error: String? = null) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val flagsMutable = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val flagsImmutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val open = PendingIntent.getActivity(
            context, notificationId(logId),
            QuickAddActivity.intent(context, logId),
            flagsImmutable,
        )
        val replyIntent = PendingIntent.getBroadcast(
            context, notificationId(logId),
            Intent(context, AmountPromptReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_LOG_ID, logId),
            flagsMutable,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context, notificationId(logId) + 1,
            Intent(context, AmountPromptReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_LOG_ID, logId),
            flagsImmutable,
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Amount and merchant, e.g. 12.50 Koufu").build()
        val replyAction = NotificationCompat.Action.Builder(0, "Enter amount", replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
        val body = error ?: "${describe(hint, postedAt)}\nType the amount (and merchant), e.g. 12.50 Koufu"

        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_receipt)
            .setContentTitle("${hint.label} — add amount?")
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(error != null)
            .addAction(replyAction)
            .addAction(0, "Not a payment", dismissIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(logId), n) }
    }

    /** Replaces the prompt with a short "saved" note (required after an inline reply, or the reply spins). */
    fun showSaved(context: Context, logId: Long, tx: Transaction) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_receipt)
            .setContentTitle("Saved ${Money.format(tx.amountCents)}")
            .setContentText(tx.merchant.ifBlank { tx.paymentMethod.label })
            .setSilent(true)
            .setTimeoutAfter(4_000)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(logId), n) }
    }

    fun cancel(context: Context, logId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(logId))
    }
}

/** Handles "Enter amount" inline replies and "Not a payment" taps. */
class AmountPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val logId = intent.getLongExtra(AmountPromptNotifier.EXTRA_LOG_ID, -1)
        if (logId < 0) return
        val container = (context.applicationContext as SgExpenseApp).container
        val service = container.amountPrompts
        val result = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    AmountPromptNotifier.ACTION_DISMISS -> service.dismiss(logId)
                    AmountPromptNotifier.ACTION_REPLY -> {
                        val reply = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(AmountPromptNotifier.KEY_REPLY)?.toString().orEmpty()
                        val pending = service.pending(logId) ?: run { AmountPromptNotifier.cancel(context, logId); return@launch }
                        val entry = QuickEntry.parse(reply)
                        if (entry == null) {
                            AmountPromptNotifier.show(
                                context, logId, pending.hint, pending.log.postedAt,
                                error = "Couldn't find an amount in \"$reply\". Try again, e.g. 12.50 Koufu",
                            )
                        } else {
                            val tx = service.complete(logId, entry.amountCents, entry.text)
                            if (tx != null) AmountPromptNotifier.showSaved(context, logId, tx)
                        }
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}
