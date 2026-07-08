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
private val EXPENSE_TYPES = setOf("Bill")
private const val MEAL_TAG = "[FODMAP meal plan]"

data class AgendaItem(
    val title: String,
    val space: String,
    val client: String,
    val date: LocalDate,
    val pri: Boolean,
    val overdue: Boolean,
)

data class GoalItem(val text: String, val target: Int, val progress: Int, val done: Boolean)

data class AgendaSummary(
    val overdueCount: Int,
    val todayItems: List<AgendaItem>,
    val upcomingItems: List<AgendaItem>,
    val billedThisMonth: Double,
    val paidThisMonth: Double,
    val goals: List<GoalItem>,
    val mealNote: String?,
    val todoTop: List<String>,
    val buyTop: List<String>,
    val fetchedAt: Long,
    val error: String? = null,
)

private fun emptySummary(error: String? = null) = AgendaSummary(
    overdueCount = 0, todayItems = emptyList(), upcomingItems = emptyList(),
    billedThisMonth = 0.0, paidThisMonth = 0.0, goals = emptyList(), mealNote = null,
    todoTop = emptyList(), buyTop = emptyList(), fetchedAt = System.currentTimeMillis(), error = error,
)

object PlannerRepository {

    /** Fetch the planner's Firestore doc and compute the whole widget summary. Never throws — errors land in AgendaSummary.error. */
    fun fetchSummary(): AgendaSummary {
        return try {
            val json = fetchDocJson()
            computeSummary(json)
        } catch (e: Exception) {
            Log.w(TAG, "fetchSummary failed", e)
            emptySummary(e.message ?: "fetch failed")
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
        val paidMap = state.optJSONObject("paid") ?: JSONObject()
        val rules = state.optJSONArray("rules") ?: JSONArray()
        val oneoffs = state.optJSONArray("oneoffs") ?: JSONArray()
        val notes = state.optJSONObject("notes") ?: JSONObject()
        val goalsArr = state.optJSONArray("goals") ?: JSONArray()
        val lists = state.optJSONArray("lists") ?: JSONArray()

        // ---- Agenda: overdue / today / upcoming (30 days back, 6 days forward) ----
        val items = mutableListOf<AgendaItem>()
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
        for (i in 0 until oneoffs.length()) {
            val o = oneoffs.getJSONObject(i)
            if (o.optBoolean("done", false)) continue
            val due = parseDateOrNull(o.optString("due", "")) ?: continue
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

        // ---- Money: this calendar month, billed/paid (mirrors moneyMini() in index.html) ----
        val monthStart = YearMonth.from(today).atDay(1)
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        var billed = 0.0
        var paid = 0.0
        var md = monthStart
        while (!md.isAfter(monthEnd)) {
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                if (!r.optBoolean("active", true)) continue
                if (!firesOn(r, md)) continue
                val amount = r.optDouble("amount", 0.0)
                if (amount <= 0.0 || r.optString("type") in EXPENSE_TYPES) continue
                val key = "${r.optString("id")}|${md.format(ISO)}"
                if (skip.optBoolean(key, false)) continue
                billed += amount
                if (paidMap.optBoolean(key, false)) paid += amount
            }
            md = md.plusDays(1)
        }
        for (i in 0 until oneoffs.length()) {
            val o = oneoffs.getJSONObject(i)
            val due = parseDateOrNull(o.optString("due", "")) ?: continue
            if (due.isBefore(monthStart) || due.isAfter(monthEnd)) continue
            val amount = o.optDouble("amount", 0.0)
            if (amount <= 0.0 || o.optString("type") in EXPENSE_TYPES) continue
            billed += amount
            if (o.optBoolean("paid", false)) paid += amount
        }

        // ---- Goals: this month only ----
        val monthKey = "%04d-%02d".format(today.year, today.monthValue)
        val goals = mutableListOf<GoalItem>()
        for (i in 0 until goalsArr.length()) {
            val g = goalsArr.getJSONObject(i)
            if (g.optString("month") != monthKey) continue
            goals.add(GoalItem(g.optString("text", ""), g.optInt("target", 0), g.optInt("progress", 0), g.optBoolean("done", false)))
        }

        // ---- Meal plan note for today ----
        val todayNote = notes.optString(today.format(ISO), "")
        val mealNote = if (todayNote.startsWith(MEAL_TAG)) todayNote.removePrefix(MEAL_TAG).trim() else null

        // ---- Lists: To-Do + Need to Buy (undone items, top 5 each) ----
        var todoTop = emptyList<String>()
        var buyTop = emptyList<String>()
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            val id = l.optString("id")
            if (id != "todo" && id != "buy") continue
            val itemsArr = l.optJSONArray("items") ?: JSONArray()
            val texts = mutableListOf<String>()
            for (j in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(j)
                if (it.optBoolean("done", false)) continue
                texts.add(it.optString("text", ""))
                if (texts.size >= 5) break
            }
            if (id == "todo") todoTop = texts else buyTop = texts
        }

        return AgendaSummary(
            overdueCount = overdue.size,
            todayItems = todays,
            upcomingItems = upcoming,
            billedThisMonth = billed,
            paidThisMonth = paid,
            goals = goals,
            mealNote = mealNote,
            todoTop = todoTop,
            buyTop = buyTop,
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
