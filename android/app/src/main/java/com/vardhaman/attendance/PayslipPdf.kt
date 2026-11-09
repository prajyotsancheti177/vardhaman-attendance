package com.vardhaman.attendance

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.time.YearMonth

/** Renders an A4 payslip PDF and opens the share sheet (WhatsApp, print, save…). */
fun sharePayslip(ctx: Context, s: Slip) {
    val doc = PdfDocument()
    val W = 595; val H = 842; val L = 48f; val R = W - 48f
    var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
    var c = page.canvas
    val p = Paint().apply { isAntiAlias = true; textSize = 11f }
    val bold = Paint(p).apply { typeface = Typeface.DEFAULT_BOLD }
    val muted = Paint(p).apply { color = 0xFF6B7280.toInt() }
    fun right(t: String, x: Float, y: Float, paint: Paint) = c.drawText(t, x - paint.measureText(t), y, paint)
    var y = 64f

    c.drawText("VARDHAMAN", L, y, Paint(bold).apply { textSize = 22f }); y += 18
    c.drawText("Salary slip · ${prettyMonth(YearMonth.parse(s.month))}", L, y, muted); y += 32
    c.drawText(s.employee.name, L, y, Paint(bold).apply { textSize = 15f })
    right("Rate ${money(s.employee.hourlyRate)}/h", R, y, muted); y += 28

    val rows = listOf(
        "Days worked" to "${s.days}", "Total hours" to hours(s.minutes),
        "Gross pay" to money(s.gross), "Advance deducted" to "- " + money(s.advanceTotal),
    )
    rows.forEach { (k, v) -> c.drawText(k, L, y, muted); right(v, R, y, p); y += 18 }
    c.drawLine(L, y - 6, R, y - 6, muted); y += 10
    c.drawText("Net pay", L, y, Paint(bold).apply { textSize = 14f })
    right(money(s.net), R, y, Paint(bold).apply { textSize = 14f }); y += 36

    c.drawText("DATE", L, y, muted); c.drawText("IN", L + 140, y, muted); c.drawText("OUT", L + 200, y, muted)
    c.drawText("LUNCH", L + 260, y, muted); right("HOURS", R - 90, y, muted); right("AMOUNT", R, y, muted); y += 16
    for (e in s.entries) {
        if (y > H - 60) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, doc.pages.size + 1).create()); c = page.canvas; y = 64f }
        c.drawText(prettyDate(e.date), L, y, p); c.drawText(e.entry, L + 140, y, p); c.drawText(e.exit, L + 200, y, p)
        c.drawText("${e.lunch}m", L + 260, y, p); right(hours(e.minutes), R - 90, y, p); right(money(e.amount), R, y, p); y += 16
    }
    if (s.advances.isNotEmpty()) {
        y += 16; c.drawText("ADVANCES", L, y, muted); y += 16
        s.advances.forEach { a -> c.drawText("${prettyDate(a.date)}  ${a.note}", L, y, p); right(money(a.amount), R, y, p); y += 16 }
    }
    doc.finishPage(page)

    val dir = File(ctx.cacheDir, "slips").apply { mkdirs() }
    val file = File(dir, "Payslip-${s.employee.name.replace(Regex("[^A-Za-z0-9]"), "_")}-${s.month}.pdf")
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    val uri = FileProvider.getUriForFile(ctx, "com.vardhaman.attendance.files", file)
    ctx.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share payslip"))
}
