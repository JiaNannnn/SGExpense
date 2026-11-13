package com.sgexpense.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CaptureSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    private val _watched = MutableStateFlow(load())
    val watched: StateFlow<Set<String>> = _watched

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** Ask the user for the amount when a UOB transaction alert doesn't include one. */
    var promptMissingAmount: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_MISSING, true)
        set(v) { prefs.edit().putBoolean(KEY_PROMPT_MISSING, v).apply() }

    /** Whether SG Expense may post its own notifications (needed for the amount prompt). */
    fun canPostNotifications(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Apps added to the defaults in a later version are switched on once for existing users,
     * without re-adding anything the user unticked.
     */
    private fun load(): Set<String> {
        val saved = prefs.getStringSet(KEY_WATCHED, null)?.toSet() ?: return DEFAULT_PACKAGES
        val offered = prefs.getStringSet(KEY_OFFERED, null)?.toSet() ?: ORIGINAL_DEFAULTS
        val newDefaults = DEFAULT_PACKAGES - offered
        if (newDefaults.isEmpty()) return saved
        val merged = saved + newDefaults
        prefs.edit().putStringSet(KEY_WATCHED, merged).putStringSet(KEY_OFFERED, DEFAULT_PACKAGES).apply()
        return merged
    }

    fun setWatched(pkgs: Set<String>) {
        prefs.edit().putStringSet(KEY_WATCHED, pkgs).putStringSet(KEY_OFFERED, DEFAULT_PACKAGES).apply()
        _watched.value = pkgs
    }

    fun isWatched(pkg: String) = pkg in _watched.value

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listenerComponent() = ComponentName(context, PaymentNotificationListener::class.java)

    fun notificationAccessIntent() = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    companion object {
        private const val KEY_WATCHED = "watched_packages"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OFFERED = "offered_defaults"
        private const val KEY_PROMPT_MISSING = "prompt_missing_amount"

        /** Known package names; extra UOB variants are harmless if not installed. */
        val KNOWN_APPS = linkedMapOf(
            "com.google.android.apps.walletnfcrel" to "Google Wallet",
            "com.uob.mighty.app" to "UOB TMRW",
            "com.uob.tmrw.sg" to "UOB TMRW (alt)",
            "com.uob.personal.app" to "UOB Personal (alt)",
            "com.shopback.app" to "ShopBack",
            "com.fairprice.mcomapp" to "FairPrice Group",
            "sg.com.transitlink" to "SimplyGo",
        )
        val DEFAULT_PACKAGES: Set<String> = KNOWN_APPS.keys

        /** Defaults shipped in v0.1.0, before FairPrice was added. */
        private val ORIGINAL_DEFAULTS = setOf(
            "com.google.android.apps.walletnfcrel", "com.uob.mighty.app", "com.uob.tmrw.sg",
            "com.uob.personal.app", "com.shopback.app",
        )
    }
}
