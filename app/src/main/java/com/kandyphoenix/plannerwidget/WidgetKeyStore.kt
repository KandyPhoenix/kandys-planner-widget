package com.kandyphoenix.plannerwidget

import android.content.Context

/**
 * The one secret the widget holds: the key the planner Worker's /widget route expects.
 * Entered once in the app (MainActivity), kept in app-private SharedPreferences, never
 * synced anywhere. Clearing the app's storage removes it.
 */
object WidgetKeyStore {
    private const val PREFS = "planner_widget"
    private const val KEY = "widget_key"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")?.trim().orEmpty()

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.trim()).apply()
    }
}
