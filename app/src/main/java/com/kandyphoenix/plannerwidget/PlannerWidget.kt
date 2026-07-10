package com.kandyphoenix.plannerwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private const val PLANNER_URL = "https://kandyphoenix.github.io/kandys-planner/"

private val BG = Color(0xFF161619)
private val TEXT = Color(0xFFF3F3F6)
private val MUTED = Color(0xFF9A9AA6)
private val PINK = Color(0xFFFF1C8D)
private val RED = Color(0xFFE5484D)
private val GREEN = Color(0xFF37D39B)

private fun spaceColor(space: String): Color = when (space) {
    "PHW" -> Color(0xFF4A9FFF)
    "Life" -> Color(0xFFA855F7)
    else -> PINK // PM
}

private fun money(v: Double): String {
    val rounded = Math.round(v)
    return "$" + "%,d".format(rounded)
}

// If the cached summary is missing or older than this, the widget refetches itself.
private const val STALE_AFTER_MS = 20 * 60 * 1000L

class PlannerWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Self-heal: whenever the widget is (re)rendered with no data yet or stale data,
        // kick off a fetch ourselves. This is what actually keeps the card populated —
        // relying only on the WorkManager periodic job is unreliable (it fires before the
        // glance id exists on first add, and OEM battery optimizers routinely kill it).
        val cached = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[SUMMARY_KEY]
        if (needsRefresh(cached)) {
            // KEEP dedupes: if a refresh is already queued/running, don't pile on another.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "planner_widget_refresh_now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().build(),
            )
        }

        provideContent {
            val prefs = currentState<Preferences>()
            val json = prefs[SUMMARY_KEY]
            WidgetContent(json)
        }
    }
}

private fun needsRefresh(json: String?): Boolean {
    if (json.isNullOrBlank()) return true
    return try {
        val fetchedAt = JSONObject(json).optLong("fetchedAt", 0L)
        System.currentTimeMillis() - fetchedAt > STALE_AFTER_MS
    } catch (e: Exception) {
        true
    }
}

private sealed class Row2 {
    data class Header(val label: String) : Row2()
    data class Item(val text: String) : Row2()
    data class Agenda(val obj: JSONObject, val showDate: Boolean) : Row2()
    data class Plain(val text: String, val color: Color) : Row2()
    object Space4 : Row2()
}

@Composable
private fun WidgetContent(json: String?) {
    val today = LocalDate.now()
    val headerDate = today.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    val openUrl = actionStartActivity(openIntent())

    Column(modifier = GlanceModifier.fillMaxSize().background(BG)) {
        // Header — not part of the scroll region, always visible.
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(14.dp, 14.dp, 14.dp, 8.dp).clickable(openUrl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Kandy's Planner", style = TextStyle(color = ColorProvider(PINK), fontWeight = FontWeight.Bold, fontSize = 22.sp))
            Spacer(modifier = GlanceModifier.width(10.dp))
            Text(text = headerDate, style = TextStyle(color = ColorProvider(MUTED), fontSize = 16.sp))
            Spacer(modifier = GlanceModifier.width(14.dp))
            Text(
                text = "⟳",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 22.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshActionCallback>()),
            )
        }

        if (json == null) {
            Text(text = "Loading…", style = TextStyle(color = ColorProvider(MUTED), fontSize = 16.sp), modifier = GlanceModifier.padding(12.dp))
            return@Column
        }
        val obj = try { JSONObject(json) } catch (e: Exception) { null }
        if (obj == null) {
            Text(text = "Couldn't load planner data", style = TextStyle(color = ColorProvider(RED), fontSize = 16.sp), modifier = GlanceModifier.padding(12.dp))
            return@Column
        }

        val rows = buildRows(obj)

        LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(rows.size) { idx ->
                RenderRow(rows[idx], today, openUrl)
            }
        }
    }
}

private fun buildRows(obj: JSONObject): List<Row2> {
    val rows = mutableListOf<Row2>()
    val error = obj.optString("error", "")
    val overdueCount = obj.optInt("overdueCount", 0)
    val todayArr = obj.optJSONArray("today")
    val upcoming = obj.optJSONArray("upcoming")
    val billed = obj.optDouble("billed", 0.0)
    val paid = obj.optDouble("paid", 0.0)
    val mealNote = obj.optString("mealNote", "")
    val goals = obj.optJSONArray("goals")
    val todo = obj.optJSONArray("todo")
    val buy = obj.optJSONArray("buy")

    if (error.isNotBlank()) rows.add(Row2.Plain("⚠ $error", RED))

    rows.add(Row2.Plain(if (overdueCount > 0) "$overdueCount overdue" else "All caught up", if (overdueCount > 0) RED else GREEN))
    rows.add(Row2.Plain("$" + "%,d".format(Math.round(billed)) + " billed  ·  " + "$" + "%,d".format(Math.round(paid)) + " paid this month", MUTED))

    if (mealNote.isNotBlank()) {
        rows.add(Row2.Space4)
        rows.add(Row2.Header("TODAY'S MEALS"))
        rows.add(Row2.Item(mealNote))
    }

    if (goals != null && goals.length() > 0) {
        rows.add(Row2.Space4)
        rows.add(Row2.Header("GOALS THIS MONTH"))
        for (i in 0 until goals.length()) {
            val g = goals.getJSONObject(i)
            val target = g.optInt("target", 0)
            val progress = g.optInt("progress", 0)
            val suffix = if (target > 0) " ($progress/$target)" else ""
            val mark = if (g.optBoolean("done", false)) "✓ " else ""
            rows.add(Row2.Item(mark + g.optString("text", "") + suffix))
        }
    }

    if (todayArr != null && todayArr.length() > 0) {
        rows.add(Row2.Space4)
        rows.add(Row2.Header("TODAY"))
        for (i in 0 until todayArr.length()) rows.add(Row2.Agenda(todayArr.getJSONObject(i), false))
    }

    if (upcoming != null && upcoming.length() > 0) {
        rows.add(Row2.Space4)
        rows.add(Row2.Header("NEXT 7 DAYS"))
        for (i in 0 until upcoming.length()) rows.add(Row2.Agenda(upcoming.getJSONObject(i), true))
    }

    if (todo != null && todo.length() > 0) {
        rows.add(Row2.Space4)
        rows.add(Row2.Header("TO-DO"))
        for (i in 0 until todo.length()) rows.add(Row2.Item(todo.getString(i)))
    }

    if (buy != null && buy.length() > 0) {
        rows.add(Row2.Space4)
        rows.add(Row2.Header("NEED TO BUY"))
        for (i in 0 until buy.length()) rows.add(Row2.Item(buy.getString(i)))
    }

    if (rows.isEmpty() || (rows.size == 2 && error.isBlank())) {
        rows.add(Row2.Item("Nothing else on the agenda"))
    }
    return rows
}

@Composable
private fun RenderRow(row: Row2, today: LocalDate, openUrl: Action) {
    when (row) {
        is Row2.Space4 -> Spacer(modifier = GlanceModifier.height(10.dp))
        is Row2.Header -> Text(
            text = row.label,
            style = TextStyle(color = ColorProvider(MUTED), fontWeight = FontWeight.Bold, fontSize = 14.sp),
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).clickable(openUrl),
        )
        is Row2.Plain -> Text(
            text = row.text,
            style = TextStyle(color = ColorProvider(row.color), fontWeight = FontWeight.Bold, fontSize = 17.sp),
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(openUrl),
        )
        is Row2.Item -> Text(
            text = "•  " + row.text,
            style = TextStyle(color = ColorProvider(TEXT), fontSize = 16.sp),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(openUrl),
        )
        is Row2.Agenda -> AgendaRow(row.obj, row.showDate, openUrl)
    }
}

@Composable
private fun AgendaRow(item: JSONObject, showDate: Boolean, openUrl: Action) {
    val space = item.optString("space", "PM")
    val title = item.optString("title", "")
    val pri = item.optBoolean("pri", false)
    val dateStr = item.optString("date", "")
    val dateLabel = if (showDate) shortDate(dateStr) else ""

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(openUrl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = GlanceModifier.width(10.dp).height(10.dp).background(spaceColor(space)))
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = (if (pri) "★ " else "") + title + (if (dateLabel.isNotBlank()) "  ·  $dateLabel" else ""),
            style = TextStyle(color = ColorProvider(TEXT), fontSize = 16.sp),
            maxLines = 1,
        )
    }
}

private fun shortDate(iso: String): String = try {
    val d = LocalDate.parse(iso.substring(0, 10))
    "${d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US)} ${d.monthValue}/${d.dayOfMonth}"
} catch (e: Exception) {
    ""
}

private fun openIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(PLANNER_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

class RefreshActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
