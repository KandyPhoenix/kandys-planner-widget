package com.kandyphoenix.plannerwidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

val SUMMARY_KEY = stringPreferencesKey("agenda_summary_json")

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/** Fetches the planner's live Firestore state, computes the widget summary, and pushes it into every widget instance. */
class RefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val summary = PlannerRepository.fetchSummary()
        val json = toJson(summary)

        val manager = GlanceAppWidgetManager(applicationContext)
        val ids = manager.getGlanceIds(PlannerWidget::class.java)
        if (ids.isEmpty()) return Result.success()

        for (id in ids) {
            updateAppWidgetState(applicationContext, androidx.glance.state.PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply { this[SUMMARY_KEY] = json }
            }
        }
        PlannerWidget().updateAll(applicationContext)

        return if (summary.error != null) Result.retry() else Result.success()
    }

    private fun toJson(s: AgendaSummary): String {
        val obj = JSONObject()
        obj.put("overdueCount", s.overdueCount)
        obj.put("fetchedAt", s.fetchedAt)
        obj.put("error", s.error)
        obj.put("today", itemsToJson(s.todayItems))
        obj.put("upcoming", itemsToJson(s.upcomingItems))
        obj.put("billed", s.billedThisMonth)
        obj.put("paid", s.paidThisMonth)
        obj.put("mealNote", s.mealNote)
        obj.put("goals", goalsToJson(s.goals))
        obj.put("todo", JSONArray(s.todoTop))
        obj.put("buy", JSONArray(s.buyTop))
        return obj.toString()
    }

    private fun itemsToJson(items: List<AgendaItem>): JSONArray {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("title", it.title)
            o.put("space", it.space)
            o.put("client", it.client)
            o.put("date", it.date.format(ISO))
            o.put("pri", it.pri)
            arr.put(o)
        }
        return arr
    }

    private fun goalsToJson(goals: List<GoalItem>): JSONArray {
        val arr = JSONArray()
        for (g in goals) {
            val o = JSONObject()
            o.put("text", g.text)
            o.put("target", g.target)
            o.put("progress", g.progress)
            o.put("done", g.done)
            arr.put(o)
        }
        return arr
    }
}
