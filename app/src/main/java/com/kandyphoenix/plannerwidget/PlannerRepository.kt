package com.kandyphoenix.plannerwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val TAG = "PlannerRepository"

// Same Firebase project/doc the web app (kandys-planner) reads and writes.
private const val FIRESTORE_URL =
    "https://firestore.googleapis.com/v1/projects/wellness-tracker-127/databases/(default)/documents/wellness/servicesPlanner" +
        "?key=AIzaSyAxqkJiZL94gR3W5TBPTRNE5AdLyCDwb2g"

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

data class AgendaItem(
    val title: String,
    val space: String,
    val client: String,
    val date: LocalDate,
    val pri: Boolean,
    val overdue: Boolean,
)

data class AgendaSummary(
    val overdueCount: Int,
    val todayItems: List<AgendaItem>,
    val upcomingItems: List<AgendaItem>,
    val fetchedAt: Long,
    val error: String? = null,
)

object PlannerRepository {

    /** Fetch the planner's Firestore doc and compute today/overdue/upcoming. Never throws — errors land in AgendaSummary.error. */
    fun fetchSummary(): AgendaSummary {
        return try {
            val json = fetchDocJson()
            computeSummary(json)
        } catch (e: Exception) {
            Log.w(TAG, "fetchSummary failed", e)
            AgendaSummary(0, emptyList(), emptyList(), System.currentTimeMillis(), error = e.message ?: "fetch failed")
        }
    }

    private fun fetchDocJson(): JSONObject {
        val conn = URL(FIRESTORE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        if (code != 200) throw RuntimeException("Firestore HTTP $code")
        val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        val doc = JSONObject(body)
        // Firestore REST wraps field values: fields.json.stringValue holds the app's JSON.stringify(S) blob.
        val stateStr = doc.getJSONObject("fields").getJSONObject("json").getString("stringValue")
        return JSONObject(stateStr)
    }

    private fun computeSummary(state: JSONObject): AgendaSummary {
        val today = LocalDate.now()
        val windowStart = today.minusDays(30)
        val windowEnd = today.plusDays(6)

        val done = state.optJSONObject("done") ?: JSONObject()
        val skip = state.optJSONObject("skip") ?: JSONObject()
        val rules = state.optJSONArray("rules") ?: JSONArray()
        val oneoffs = state.optJSONArray("oneoffs") ?: JSONArray()

        val items = mutableListOf<AgendaItem>()

        // Recurring rules — day-by-day scan across the window (cheap: ~37 days x few rules).
        var d = windowStart
        while (!d.isAfter(windowEnd)) {
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                if (!r.optBoolean("active", true)) continue
                if (firesOn(r, d)) {
                    val key = "${r.optString("id")}|${d.format(ISO)}"
                    if (skip.optBoolean(key, false)) continue
                    if (done.optBoolean(key, false)) continue
                    items.add(
                        AgendaItem(
                            title = r.optString("title", "(untitled)"),
                            space = r.optString("space", "PM"),
                            client = r.optString("client", ""),
                            date = d,
                            pri = r.optBoolean("pri", false),
                            overdue = d.isBefore(today),
                        )
                    )
                }
            }
            d = d.plusDays(1)
        }

        // One-off tasks.
        for (i in 0 until oneoffs.length()) {
            val o = oneoffs.getJSONObject(i)
            if (o.optBoolean("done", false)) continue
            val dueStr = o.optString("due", "")
            val due = parseDateOrNull(dueStr) ?: continue
            if (due.isBefore(windowStart) || due.isAfter(windowEnd)) continue
            items.add(
                AgendaItem(
                    title = o.optString("title", "(untitled)"),
                    space = o.optString("space", "PM"),
                    client = o.optString("client", ""),
                    date = due,
                    pri = o.optBoolean("pri", false),
                    overdue = due.isBefore(today),
                )
            )
        }

        val overdue = items.filter { it.overdue }.sortedBy { it.date }
        val todays = items.filter { it.date == today }.sortedBy { !it.pri }
        val upcoming = items.filter { it.date.isAfter(today) }.sortedWith(compareBy({ it.date }, { !it.pri }))

        return AgendaSummary(
            overdueCount = overdue.size,
            todayItems = todays,
            upcomingItems = upcoming,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun parseDateOrNull(s: String): LocalDate? = try {
        if (s.length < 10) null else LocalDate.parse(s.substring(0, 10), ISO)
    } catch (e: Exception) {
        null
    }

    /** Mirrors instancesIn()/inst() date logic from the web app's index.html. */
    private fun firesOn(rule: JSONObject, d: LocalDate): Boolean {
        val startStr = rule.optString("start", "")
        val start = if (startStr.isNotBlank()) parseDateOrNull(startStr) else null
        if (start != null && d.isBefore(start)) return false

        return when (rule.optString("freq")) {
            "weekly" -> {
                val jsDow = d.dayOfWeek.value % 7 // JS getDay(): Sun=0..Sat=6
                jsDow == rule.optInt("dow", -1)
            }
            "biweekly" -> {
                val anchorStr = rule.optString("anchor", startStr)
                val anchor = parseDateOrNull(anchorStr) ?: return false
                val diff = ChronoUnit.DAYS.between(anchor, d)
                Math.floorMod(diff, 14L) == 0L
            }
            "monthly" -> {
                val dom = rule.optInt("dom", 1)
                val lastDom = YearMonth.of(d.year, d.monthValue).lengthOfMonth()
                d.dayOfMonth == minOf(dom, lastDom)
            }
            "quarterly" -> {
                val dom = rule.optInt("dom", 1)
                val lastDom = YearMonth.of(d.year, d.monthValue).lengthOfMonth()
                if (d.dayOfMonth != minOf(dom, lastDom)) return false
                val ancIdx = if (start != null) start.year * 12 + (start.monthValue - 1) else (d.year * 12 + (d.monthValue - 1))
                val idx = d.year * 12 + (d.monthValue - 1)
                Math.floorMod(idx - ancIdx, 3) == 0
            }
            "yearly" -> {
                val month0 = rule.optInt("month", 0) // JS months are 0-based
                val dom = rule.optInt("dom", 1)
                val lastDom = YearMonth.of(d.year, month0 + 1).lengthOfMonth()
                (d.monthValue - 1) == month0 && d.dayOfMonth == minOf(dom, lastDom)
            }
            else -> false
        }
    }
}
