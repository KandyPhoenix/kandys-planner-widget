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
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private const val PLANNER_URL = "https://kandyphoenix.github.io/kandys-planner/"

private val BG = Color(0xFF161619)
private val PANEL = Color(0xFF1F1F24)
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

class PlannerWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val json = prefs[SUMMARY_KEY]
            WidgetContent(json)
        }
    }
}

@Composable
private fun WidgetContent(json: String?) {
    val today = LocalDate.now()
    val headerDate = today.format(DateTimeFormatter.ofPattern("EEE, MMM d"))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BG)
            .padding(12.dp)
            .clickable(actionStartActivity(openIntent()))
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Kandy's Planner",
                style = TextStyle(color = ColorProvider(PINK), fontWeight = FontWeight.Bold, fontSize = 14.sp),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(text = headerDate, style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp))
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "⟳",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 14.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshActionCallback>()),
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))

        if (json == null) {
            Text(text = "Loading…", style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
            return@Column
        }

        val obj = try { JSONObject(json) } catch (e: Exception) { null }
        if (obj == null) {
            Text(text = "Couldn't load planner data", style = TextStyle(color = ColorProvider(RED), fontSize = 12.sp))
            return@Column
        }

        val error = obj.optString("error", "")
        val overdueCount = obj.optInt("overdueCount", 0)
        val today0 = obj.optJSONArray("today")
        val upcoming = obj.optJSONArray("upcoming")

        if (error.isNotBlank()) {
            Text(text = "⚠ $error", style = TextStyle(color = ColorProvider(RED), fontSize = 11.sp))
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (overdueCount > 0) "$overdueCount overdue" else "All caught up",
                style = TextStyle(
                    color = ColorProvider(if (overdueCount > 0) RED else GREEN),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))

        var shown = 0
        val maxItems = 6

        if (today0 != null && today0.length() > 0) {
            Text(text = "TODAY", style = TextStyle(color = ColorProvider(MUTED), fontWeight = FontWeight.Bold, fontSize = 10.sp))
            for (i in 0 until today0.length()) {
                if (shown >= maxItems) break
                AgendaRow(today0.getJSONObject(i), showDate = false)
                shown++
            }
        }

        if (upcoming != null && upcoming.length() > 0 && shown < maxItems) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(text = "UPCOMING", style = TextStyle(color = ColorProvider(MUTED), fontWeight = FontWeight.Bold, fontSize = 10.sp))
            for (i in 0 until upcoming.length()) {
                if (shown >= maxItems) break
                AgendaRow(upcoming.getJSONObject(i), showDate = true)
                shown++
            }
        }

        if (shown == 0 && error.isBlank()) {
            Text(text = "Nothing on the agenda", style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
        }
    }
}

@Composable
private fun AgendaRow(item: JSONObject, showDate: Boolean) {
    val space = item.optString("space", "PM")
    val title = item.optString("title", "")
    val pri = item.optBoolean("pri", false)
    val dateStr = item.optString("date", "")
    val dateLabel = if (showDate) shortDate(dateStr) else ""

    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = GlanceModifier
                .width(6.dp)
                .height(6.dp)
                .background(spaceColor(space))
        )
        Spacer(modifier = GlanceModifier.width(5.dp))
        Text(
            text = (if (pri) "★ " else "") + title,
            style = TextStyle(color = ColorProvider(TEXT), fontSize = 11.sp),
            maxLines = 1,
        )
        if (dateLabel.isNotBlank()) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(text = dateLabel, style = TextStyle(color = ColorProvider(MUTED), fontSize = 10.sp))
        }
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
