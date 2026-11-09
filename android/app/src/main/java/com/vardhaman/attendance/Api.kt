package com.vardhaman.attendance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Employee(val id: Long, val name: String, val phone: String, val hourlyRate: Double, val active: Boolean)

data class Entry(
    val id: Long, val employeeId: Long, val date: String,
    val entry: String, val exit: String, val lunch: Int,
    val rate: Double, val minutes: Int, val amount: Double,
)

data class Advance(val id: Long, val employeeId: Long, val amount: Double, val date: String, val note: String)

data class Slip(
    val employee: Employee, val month: String, val entries: List<Entry>, val advances: List<Advance>,
    val days: Int, val minutes: Int, val gross: Double, val advanceTotal: Double, val net: Double,
)

class ApiError(msg: String) : Exception(msg)

object Api {
    var pin: String = ""

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val c = URL(BuildConfig.API_BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("x-admin-pin", pin)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!ok) throw ApiError(runCatching { JSONObject(text).getString("error") }.getOrDefault("Error ${c.responseCode}"))
            text
        }

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else getString(k)
    private fun <T> JSONArray.map(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }

    private fun emp(o: JSONObject) = Employee(o.getLong("id"), o.str("name"), o.str("phone"), o.getDouble("hourly_rate"), o.getBoolean("active"))
    private fun entry(o: JSONObject) = Entry(
        o.getLong("id"), o.getLong("employee_id"), o.str("work_date"),
        o.str("entry_time").take(5), o.str("exit_time").take(5), o.getInt("lunch_minutes"),
        o.getDouble("hourly_rate"), o.getInt("worked_minutes"), o.getDouble("amount"),
    )
    private fun adv(o: JSONObject) = Advance(o.getLong("id"), o.getLong("employee_id"), o.getDouble("amount"), o.str("given_on"), o.str("note"))

    suspend fun ping() { call("GET", "/ping") }

    suspend fun employees(activeOnly: Boolean = false) =
        JSONArray(call("GET", "/employees" + if (activeOnly) "?active=true" else "")).map(::emp)

    suspend fun saveEmployee(id: Long?, name: String, phone: String, rate: Double, active: Boolean) {
        val b = JSONObject().put("name", name).put("phone", phone).put("hourly_rate", rate).put("active", active)
        if (id == null) call("POST", "/employees", b) else call("PUT", "/employees/$id", b)
    }

    suspend fun entriesForDate(date: String) = JSONArray(call("GET", "/entries?date=$date")).map(::entry)
    suspend fun entriesForMonth(empId: Long, month: String) =
        JSONArray(call("GET", "/entries?employee_id=$empId&month=$month")).map(::entry)

    suspend fun saveEntry(empId: Long, date: String, entry: String, exit: String, lunch: Int) {
        call("PUT", "/entries", JSONObject().put("employee_id", empId).put("work_date", date)
            .put("entry_time", entry).put("exit_time", exit).put("lunch_minutes", lunch))
    }
    suspend fun deleteEntry(id: Long) { call("DELETE", "/entries/$id") }

    suspend fun advances(month: String) = JSONArray(call("GET", "/advances?month=$month")).map(::adv)
    suspend fun addAdvance(empId: Long, amount: Double, date: String, note: String) {
        call("POST", "/advances", JSONObject().put("employee_id", empId).put("amount", amount).put("given_on", date).put("note", note))
    }
    suspend fun deleteAdvance(id: Long) { call("DELETE", "/advances/$id") }

    suspend fun payslip(empId: Long, month: String): Slip {
        val o = JSONObject(call("GET", "/payslip?employee_id=$empId&month=$month"))
        return Slip(
            emp(o.getJSONObject("employee")), o.getString("month"),
            o.getJSONArray("entries").map(::entry), o.getJSONArray("advances").map(::adv),
            o.getInt("days_worked"), o.getInt("total_minutes"), o.getDouble("gross"), o.getDouble("advance_total"), o.getDouble("net"),
        )
    }

    suspend fun summary(month: String) = JSONArray(call("GET", "/summary?month=$month")).map { o ->
        Slip(emp(o.getJSONObject("employee")), month, emptyList(), emptyList(), o.getInt("days_worked"),
            o.getInt("total_minutes"), o.getDouble("gross"), o.getDouble("advance_total"), o.getDouble("net"))
    }
}
