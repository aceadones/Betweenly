package com.nextlayer.betweenly.ui

import android.content.Context

/**
 * Remembers that the first-run explanation was finished.
 *
 * Kept in its own preferences file ("ui") so [com.nextlayer.betweenly.Store.unpair]
 * does not send the person through the explanation again. The name and photo
 * stay in [com.nextlayer.betweenly.Store].
 *
 * DATA-FLOW: this object never talks to the network.
 */
object UiFlags {
    private const val PREFS = "ui"
    private const val DONE = "onboarding_done"

    fun onboardingDone(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(DONE, false)

    fun setOnboardingDone(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DONE, true)
            .commit()
    }
}
