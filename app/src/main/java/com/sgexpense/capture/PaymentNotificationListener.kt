package com.sgexpense.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sgexpense.SgExpenseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reads notifications from the watched payment apps only; everything else is ignored immediately. */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val container = (application as SgExpenseApp).container
        val settings = container.captureSettings
        if (!settings.enabled || !settings.isWatched(sbn.packageName)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: lines
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return

        val pkg = sbn.packageName
        val postedAt = sbn.postTime
        scope.launch {
            runCatching { container.captureProcessor.process(pkg, title, text, postedAt) }
                .onFailure { Log.e("SgExpense", "capture failed", it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
