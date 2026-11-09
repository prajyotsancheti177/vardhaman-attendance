package com.vardhaman.attendance

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        Api.pin = prefs.getString("pin", "") ?: ""
        setContent { AppTheme { Root(startLoggedIn = Api.pin.isNotEmpty()) } }
    }
}

@Composable
fun Root(startLoggedIn: Boolean) {
    var loggedIn by remember { mutableStateOf(startLoggedIn) }
    if (!loggedIn) LoginScreen { loggedIn = true } else Shell(onLogout = { loggedIn = false })
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vardhaman", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Attendance & payroll", color = Muted)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(8); error = null }, label = { Text("Admin PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), isError = error != null,
            supportingText = { error?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = pin.length >= 4 && !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                busy = true
                scope.launch {
                    Api.pin = pin
                    try {
                        Api.employees()
                        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("pin", pin).apply()
                        onDone()
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            },
        ) { Text(if (busy) "Checking…" else "Unlock") }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Attendance("Attendance", Icons.Default.EventAvailable),
    Employees("Employees", Icons.Default.Groups),
    Advances("Advances", Icons.Default.Payments),
    Payslips("Payslips", Icons.Default.ReceiptLong),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shell(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val runner = remember { Runner(scope, snack) }
    var tab by rememberSaveable { mutableStateOf(Tab.Attendance) }
    // Shared employee list, refreshed whenever an employee is saved.
    var employees by remember { mutableStateOf<List<Employee>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { runCatching { employees = Api.employees() }.onFailure { snack.showSnackbar(it.message ?: "") } }

    CompositionLocalProvider(LocalRunner provides runner) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tab.label, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = {
                            ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().remove("pin").apply()
                            Api.pin = ""; onLogout()
                        }) { Icon(Icons.Default.Logout, "Lock") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface0),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                    }
                }
            },
            snackbarHost = { SnackbarHost(snack) },
            containerColor = Surface0,
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.Attendance -> AttendanceScreen(employees)
                    Tab.Employees -> EmployeesScreen(employees) { version++ }
                    Tab.Advances -> AdvancesScreen(employees)
                    Tab.Payslips -> PayslipsScreen(employees)
                }
            }
        }
    }
}

// ================= Attendance =================
@Composable
fun AttendanceScreen(employees: List<Employee>) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            listOf("By day", "By employee").forEachIndexed { i, l ->
                SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
        if (mode == 0) DayView(employees.filter { it.active }) else EmployeeMonthView(employees)
    }
}

/** Company × day: every active employee for one date. */
@Composable
fun DayView(employees: List<Employee>) {
    val runner = LocalRunner.current
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var entries by remember { mutableStateOf<Map<Long, Entry>>(emptyMap()) }
    var editing by remember { mutableStateOf<EntryDraft?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(date, reload) { runner.run { entries = Api.entriesForDate(date).associateBy { it.employeeId } } }

    val d = LocalDate.parse(date)
    Stepper(
        if (d == LocalDate.now()) "Today · ${prettyDate(date)}" else prettyDate(date),
        { date = d.minusDays(1).toString() }, { date = d.plusDays(1).toString() },
    )
    val logged = employees.count { entries.containsKey(it.id) }
    val total = employees.sumOf { entries[it.id]?.minutes ?: 0 }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$logged of ${employees.size} logged", color = Muted)
        Text("${hours(total)} h total", color = Muted, style = Num)
    }
    if (employees.isEmpty()) EmptyState("Add employees first")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(employees, key = { it.id }) { e ->
            val en = entries[e.id]
            PressCard({ editing = EntryDraft(e, date, en) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            if (en == null) "Tap to log" else "${to12h(en.entry)} – ${to12h(en.exit)} · lunch ${en.lunch}m",
                            color = if (en == null) Brand else Muted, fontSize = 13.sp, style = Num,
                        )
                    }
                    if (en != null) Text(hours(en.minutes), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, style = Num)
                    else Icon(Icons.Default.Add, null, tint = Brand)
                }
            }
        }
    }
    editing?.let { EntryEditor(it, { editing = null }) { editing = null; reload++ } }
}

/** Employee × month: one employee's whole month, every day listed. */
@Composable
fun EmployeeMonthView(employees: List<Employee>) {
    val runner = LocalRunner.current
    var empId by rememberSaveable { mutableStateOf<Long?>(null) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var entries by remember { mutableStateOf<Map<String, Entry>>(emptyMap()) }
    var editing by remember { mutableStateOf<EntryDraft?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val emp = employees.find { it.id == empId } ?: employees.firstOrNull()?.also { empId = it.id }
    val ym = YearMonth.parse(month)
    LaunchedEffect(emp?.id, month, reload) {
        if (emp != null) runner.run { entries = Api.entriesForMonth(emp.id, month).associateBy { it.date } }
    }
    if (emp == null) { EmptyState("Add employees first"); return }

    EmployeePicker(employees, emp) { empId = it.id }
    Stepper(prettyMonth(ym), { month = ym.minusMonths(1).toString() }, { month = ym.plusMonths(1).toString() })
    val mins = entries.values.sumOf { it.minutes }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${entries.size} days", color = Muted)
        Text("${hours(mins)} h · ${money(entries.values.sumOf { it.amount })}", color = Muted, style = Num)
    }
    val today = LocalDate.now()
    val days = (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { !it.isAfter(today) }.reversed()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(days, key = { it.toString() }) { day ->
            val en = entries[day.toString()]
            PressCard({ editing = EntryDraft(emp, day.toString(), en) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(prettyDate(day.toString()), Modifier.width(110.dp),
                        color = if (day.dayOfWeek.value == 7) Bad else Ink, fontWeight = FontWeight.Medium)
                    Text(
                        if (en == null) "—" else "${to12h(en.entry)} – ${to12h(en.exit)}",
                        Modifier.weight(1f), color = Muted, fontSize = 13.sp, style = Num,
                    )
                    Text(en?.let { hours(it.minutes) } ?: "", fontWeight = FontWeight.SemiBold, style = Num)
                }
            }
        }
    }
    editing?.let { EntryEditor(it, { editing = null }) { editing = null; reload++ } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeePicker(employees: List<Employee>, selected: Employee?, label: String = "Employee", onPick: (Employee) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            selected?.name ?: "", {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            employees.forEach { e -> DropdownMenuItem(text = { Text(e.name) }, onClick = { onPick(e); open = false }) }
        }
    }
}

// ================= Employees =================
@Composable
fun EmployeesScreen(employees: List<Employee>, onChanged: () -> Unit) {
    var editing by remember { mutableStateOf<Employee?>(null) }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (employees.isEmpty()) EmptyState("No employees yet")
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(employees, key = { it.id }) { e ->
                PressCard({ editing = e }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = if (e.active) Ink else Muted)
                            Text(if (e.active) e.phone.ifEmpty { "—" } else "Inactive", color = Muted, fontSize = 13.sp)
                        }
                        Text("${money(e.hourlyRate)}/h", style = Num, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true }, icon = { Icon(Icons.Default.PersonAdd, null) }, text = { Text("Add employee") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding || editing != null) EmployeeDialog(editing, { adding = false; editing = null }) { adding = false; editing = null; onChanged() }
}

@Composable
fun EmployeeDialog(emp: Employee?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val runner = LocalRunner.current
    var name by remember { mutableStateOf(emp?.name ?: "") }
    var phone by remember { mutableStateOf(emp?.phone ?: "") }
    var rate by remember { mutableStateOf(emp?.hourlyRate?.let { if (it % 1 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    var active by remember { mutableStateOf(emp?.active ?: true) }
    val valid = name.isNotBlank() && rate.toDoubleOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (emp == null) "New employee" else "Edit employee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(rate, { rate = it }, label = { Text("Salary per hour (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (emp != null) Text("New rate applies to days logged from now on") })
                if (emp != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active", Modifier.weight(1f)); Switch(active, { active = it })
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                runner.run("Saved") { Api.saveEmployee(emp?.id, name.trim(), phone.trim(), rate.toDouble(), active); onSaved() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================= Advances =================
@Composable
fun AdvancesScreen(employees: List<Employee>) {
    val runner = LocalRunner.current
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var list by remember { mutableStateOf<List<Advance>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Advance?>(null) }
    val ym = YearMonth.parse(month)
    LaunchedEffect(month, reload) { runner.run { list = Api.advances(month) } }
    val names = employees.associate { it.id to it.name }

    Box(Modifier.fillMaxSize()) {
        Column {
            Stepper(prettyMonth(ym), { month = ym.minusMonths(1).toString() }, { month = ym.plusMonths(1).toString() })
            Text("Total ${money(list.sumOf { it.amount })}", Modifier.padding(horizontal = 20.dp), color = Muted, style = Num)
            if (list.isEmpty()) EmptyState("No advances this month")
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { a ->
                    PressCard({ confirmDelete = a }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(names[a.employeeId] ?: "?", fontWeight = FontWeight.SemiBold)
                                Text(prettyDate(a.date) + if (a.note.isNotEmpty()) " · ${a.note}" else "", color = Muted, fontSize = 13.sp)
                            }
                            Text(money(a.amount), style = Num, fontWeight = FontWeight.SemiBold, color = Bad)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Give advance") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) AdvanceDialog(employees.filter { it.active }, { adding = false }) { adding = false; reload++ }
    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete advance?") },
            text = { Text("${money(a.amount)} to ${names[a.employeeId]} on ${prettyDate(a.date)}") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null; runner.run("Deleted") { Api.deleteAdvance(a.id); reload++ }
            }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun AdvanceDialog(employees: List<Employee>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val runner = LocalRunner.current
    var emp by remember { mutableStateOf(employees.firstOrNull()) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Give advance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.offset(x = (-16).dp)) { EmployeePicker(employees, emp) { emp = it } }
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedButton(onClick = {
                    android.app.DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                        date.year, date.monthValue - 1, date.dayOfMonth).show()
                }) { Icon(Icons.Default.CalendarToday, null); Spacer(Modifier.width(8.dp)); Text(prettyDate(date.toString())) }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = emp != null && (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                runner.run("Advance recorded") { Api.addAdvance(emp!!.id, amount.toDouble(), date.toString(), note.trim()); onSaved() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================= Payslips =================
@Composable
fun PayslipsScreen(employees: List<Employee>) {
    val runner = LocalRunner.current
    val ctx = LocalContext.current
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var rows by remember { mutableStateOf<List<Slip>>(emptyList()) }
    var open by remember { mutableStateOf<Slip?>(null) }
    val ym = YearMonth.parse(month)
    LaunchedEffect(month) { runner.run { rows = Api.summary(month) } }

    open?.let { slip -> PayslipDetail(slip, onBack = { open = null }, onShare = { sharePayslip(ctx, slip) }); return }

    Column {
        Stepper(prettyMonth(ym), { month = ym.minusMonths(1).toString() }, { month = ym.plusMonths(1).toString() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Net payable", color = Muted)
            Text(money(rows.sumOf { it.net }), style = Num, fontWeight = FontWeight.SemiBold)
        }
        if (rows.isEmpty()) EmptyState("Nothing logged this month")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.employee.id }) { s ->
                PressCard({ runner.run { open = Api.payslip(s.employee.id, month) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.employee.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("${s.days} days · ${hours(s.minutes)} h" +
                                if (s.advanceTotal > 0) " · adv ${money(s.advanceTotal)}" else "",
                                color = Muted, fontSize = 13.sp, style = Num)
                        }
                        Text(money(s.net), style = Num, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            color = if (s.net < 0) Bad else Ink)
                    }
                }
            }
        }
    }
}

@Composable
fun PayslipDetail(s: Slip, onBack: () -> Unit, onShare: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(s.employee.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(prettyMonth(YearMonth.parse(s.month)), color = Muted)
                }
                FilledTonalButton(onClick = onShare) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("PDF") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlipLine("Days worked", "${s.days}")
                    SlipLine("Total hours", hours(s.minutes))
                    SlipLine("Gross pay", money(s.gross))
                    SlipLine("Advance deducted", "− " + money(s.advanceTotal), Bad)
                    HorizontalDivider(color = Line)
                    SlipLine("Net pay", money(s.net), if (s.net < 0) Bad else Good, big = true)
                }
            }
        }
        item { SectionTitle("Daily log", Modifier.padding(top = 4.dp)) }
        items(s.entries, key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(prettyDate(e.date), Modifier.width(100.dp))
                Text("${e.entry}–${e.exit}", Modifier.weight(1f), color = Muted, style = Num)
                Text(hours(e.minutes), Modifier.width(52.dp), style = Num)
                Text(money(e.amount), Modifier.width(80.dp), style = Num, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        if (s.advances.isNotEmpty()) {
            item { SectionTitle("Advances", Modifier.padding(top = 8.dp)) }
            items(s.advances, key = { "a" + it.id }) { a ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(prettyDate(a.date), Modifier.width(100.dp))
                    Text(a.note, Modifier.weight(1f), color = Muted)
                    Text(money(a.amount), style = Num, color = Bad)
                }
            }
        }
    }
}

@Composable
fun SlipLine(label: String, value: String, color: androidx.compose.ui.graphics.Color = Ink, big: Boolean = false) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (big) Ink else Muted, fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, style = Num, color = color, fontWeight = FontWeight.SemiBold, fontSize = if (big) 20.sp else 15.sp)
    }
