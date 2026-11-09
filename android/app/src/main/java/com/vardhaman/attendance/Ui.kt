package com.vardhaman.attendance

import android.app.TimePickerDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Theme ----------
val Ink = Color(0xFF1B1F23)
val Muted = Color(0xFF6B7280)
val Line = Color(0xFFE6E8EB)
val Surface0 = Color(0xFFF6F7F9)
val Brand = Color(0xFF1F5EFF)
val Good = Color(0xFF0F8A4F)
val Bad = Color(0xFFC2410C)

private val scheme = lightColorScheme(
    primary = Brand, onPrimary = Color.White, background = Surface0, surface = Color.White,
    onSurface = Ink, onBackground = Ink, surfaceVariant = Surface0, outline = Line, error = Bad,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

/** Tabular figures so numbers line up in columns. */
val Num = TextStyle(fontFeatureSettings = "tnum")

// ---------- Formatting ----------
private val inr = java.text.NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
fun money(v: Double): String = inr.format(v)
private val inr2 = java.text.NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }
/** Hourly rate keeps its paise, e.g. ₹62.5. */
fun rate(v: Double): String = inr2.format(v)
fun hours(min: Int) = "%d:%02d".format(min / 60, min % 60)
fun prettyDate(iso: String): String = LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE, d MMM"))
fun monthName(month: String): String = YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMMM"))
/** Every date of the slip's month paired with its entry (null = absent). */
fun slipDays(s: Slip): List<Pair<String, Entry?>> {
    val ym = YearMonth.parse(s.month); val byDate = s.entries.associateBy { it.date }
    return (1..ym.lengthOfMonth()).map { ym.atDay(it).toString() }.map { it to byDate[it] }
}
fun prettyMonth(ym: YearMonth): String = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
fun minutesBetween(a: String, b: String, lunch: Int, extra: Int = 0): Int {
    fun m(t: String) = t.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    return (m(b) - m(a) - lunch - extra).coerceAtLeast(0)
}

// ---------- Pressable surface: subtle scale on press = instant feedback ----------
@Composable
fun PressCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(140), label = "press")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(interactionSource = src, indication = ripple(color = Brand)) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)

@Composable
fun Stepper(label: String, onPrev: () -> Unit, onNext: () -> Unit, onLabel: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(androidx.compose.material.icons.Icons.Default.ChevronLeft, "Previous") }
        Text(
            label, Modifier.weight(1f).let { if (onLabel != null) it.clickable { onLabel() } else it }.padding(vertical = 8.dp),
            fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) { Icon(androidx.compose.material.icons.Icons.Default.ChevronRight, "Next") }
    }
}

@Composable
fun EmptyState(text: String) = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Muted)
}

/** Runs a suspend action, surfacing errors through a snackbar. */
class Runner(private val scope: kotlinx.coroutines.CoroutineScope, private val snack: SnackbarHostState) {
    fun run(success: String? = null, block: suspend () -> Unit) = scope.launch {
        try { block(); if (success != null) snack.showSnackbar(success) }
        catch (e: Exception) { snack.showSnackbar(e.message ?: "Something went wrong") }
    }
}
val LocalRunner = staticCompositionLocalOf<Runner> { error("no runner") }

/** Button label that turns into a spinner while a request is in flight. */
@Composable
fun BusyLabel(busy: Boolean, text: String) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp)); Text("Saving…")
    } else Text(text)
}

/** Thin bar shown under the header while a list is loading. */
@Composable
fun LoadingBar(loading: Boolean) =
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Brand, trackColor = Line)
    else Spacer(Modifier.height(2.dp))

// ---------- Time field ----------
@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Surface0)
            .clickable {
                val (h, m) = value.split(":").map { it.toInt() }
                TimePickerDialog(ctx, { _, hh, mm -> onChange("%02d:%02d".format(hh, mm)) }, h, m, false).show()
            }.padding(12.dp),
    ) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(to12h(value), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = Num)
    }
}
fun to12h(t: String): String {
    val (h, m) = t.split(":").map { it.toInt() }
    return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}

// ---------- Entry editor (shared by Day view and Employee-month view) ----------
data class EntryDraft(val employee: Employee, val date: String, val existing: Entry?)

object Defaults { var entry = "09:00"; var exit = "18:00"; var lunch = 30 }

@Composable
fun EntryEditor(draft: EntryDraft, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val runner = LocalRunner.current
    var entry by remember { mutableStateOf(draft.existing?.entry ?: Defaults.entry) }
    var exit by remember { mutableStateOf(draft.existing?.exit ?: Defaults.exit) }
    var lunch by remember { mutableStateOf((draft.existing?.lunch ?: Defaults.lunch).toString()) }
    var extra by remember { mutableStateOf((draft.existing?.extra ?: 0).toString()) }
    var busy by remember { mutableStateOf(false) }
    val lunchMin = lunch.toIntOrNull() ?: 0
    val extraMin = extra.toIntOrNull() ?: 0
    val worked = minutesBetween(entry, exit, lunchMin, extraMin)
    val valid = exit > entry && worked > 0

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Column {
                Text(draft.employee.name, fontWeight = FontWeight.SemiBold)
                Text(prettyDate(draft.date), color = Muted, fontSize = 14.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField("Entry", entry, { entry = it }, Modifier.weight(1f))
                    TimeField("Exit", exit, { exit = it }, Modifier.weight(1f))
                }
                Text("Lunch break", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(0, 30, 45, 60).forEach { p ->
                        FilterChip(selected = lunchMin == p, onClick = { lunch = p.toString() }, label = { Text("$p") })
                    }
                    OutlinedTextField(
                        lunch, { lunch = it.filter(Char::isDigit).take(3) }, Modifier.width(72.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = Num,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Extra break (min)", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        extra, { extra = it.filter(Char::isDigit).take(3) }, Modifier.width(72.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = Num,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface0).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Worked", color = Muted)
                    Text(
                        if (valid) "${hours(worked)} h  ·  ${money(worked / 60.0 * (draft.existing?.rate ?: draft.employee.hourlyRate))}"
                        else "Exit must be after entry",
                        style = Num, fontWeight = FontWeight.SemiBold, color = if (valid) Ink else Bad,
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = {
                busy = true
                runner.run("Saved") {
                    try {
                        Api.saveEntry(draft.employee.id, draft.date, entry, exit, lunchMin, extraMin)
                        Defaults.entry = entry; Defaults.exit = exit; Defaults.lunch = lunchMin
                        onSaved()
                    } finally { busy = false }
                }
            }) { BusyLabel(busy, "Save") }
        },
        dismissButton = {
            Row {
                if (draft.existing != null) TextButton(enabled = !busy, onClick = {
                    busy = true
                    runner.run("Entry removed") { try { Api.deleteEntry(draft.existing.id); onSaved() } finally { busy = false } }
                }) { Text("Delete", color = Bad) }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
