package com.sgexpense.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sgexpense.SgExpenseApp
import kotlinx.coroutines.launch

/**
 * Debug builds only: feeds a fake notification through the capture pipeline as if it came from any app.
 * Protected by android.permission.DUMP, so only `adb shell` can send it:
 *
 *   adb shell am broadcast -n com.sgexpense/.debug.SimulateNotificationReceiver \
 *     --es pkg com.uob.mighty.app --es title "PayNow" --es text "You have sent money to JOHN TAN via PayNow."
 */
class SimulateNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val title = intent.getStringExtra("title").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()
        val container = (context.applicationContext as SgExpenseApp).container
        val result = goAsync()
        container.appScope.launch {
            try {
                container.captureProcessor.process(pkg, title, text, System.currentTimeMillis())
            } finally {
                result.finish()
            }
        }
    }
}
