package dev.mostlime.autocorrect.ime

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors

/**
 * Usage meter for settings -> Performance, kept in the keyboard process and written to a small file every few seconds
 * (the settings screen runs in the app process): ms per word, ms per idle pass, passes per minute, CPU % of the
 * keyboard process. Recording is a few array writes; the file write happens on its own thread.
 */
object PerfStats {
    private const val KEEP = 300
    private val wordMs = ArrayDeque<Double>()
    private val passes = ArrayDeque<LongArray>()   // [end time ms, duration ms]
    private var lastSave = 0L
    private var cpu0 = -1L
    private var wall0 = 0L
    private var cpuPct = -1.0
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ac-perf").apply { priority = Thread.MIN_PRIORITY } }

    @Synchronized fun word(ms: Double, ctx: Context) {
        wordMs.addLast(ms); while (wordMs.size > KEEP) wordMs.removeFirst()
        maybeSave(ctx)
    }

    @Synchronized fun pass(ms: Long, ctx: Context) {
        passes.addLast(longArrayOf(System.currentTimeMillis(), ms)); while (passes.size > KEEP) passes.removeFirst()
        maybeSave(ctx)
    }

    @Synchronized fun reset(ctx: Context) { wordMs.clear(); passes.clear(); cpu0 = -1; save(ctx) }

    private fun cpuTicks(): Long = runCatching {
        val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        f[11].toLong() + f[12].toLong()   // utime + stime
    }.getOrDefault(-1L)

    private fun maybeSave(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSave < 3000) return
        lastSave = now
        save(ctx)
    }

    private fun pct(xs: List<Double>, q: Double) = if (xs.isEmpty()) 0.0 else xs.sorted()[(xs.size * q).toInt().coerceAtMost(xs.size - 1)]

    private fun save(ctx: Context) {
        val now = System.currentTimeMillis()
        val t = cpuTicks()
        if (cpu0 >= 0 && t >= 0 && now > wall0) {
            val hz = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1)
            cpuPct = 100.0 * (t - cpu0) * 1000.0 / hz / (now - wall0)
        }
        cpu0 = t; wall0 = now
        val w = wordMs.toList()
        val p = passes.toList()
        val recent = p.count { now - it[0] <= 60_000 }
        val pd = p.map { it[1].toDouble() }
        val json = "{\"words\":${w.size},\"word_avg\":%.1f,\"word_p95\":%.1f,\"passes\":${p.size},\"pass_avg\":%.0f,\"pass_p95\":%.0f,\"passes_per_min\":$recent,\"cpu\":%.1f,\"time\":$now}".format(
            w.average().takeIf { !it.isNaN() } ?: 0.0, pct(w, 0.95), pd.average().takeIf { !it.isNaN() } ?: 0.0, pct(pd, 0.95), cpuPct)
        val f = file(ctx)
        io.execute { runCatching { File(f.path + ".tmp").apply { writeText(json) }.renameTo(f) } }
    }

    fun file(ctx: Context) = File(ctx.filesDir, "perf_stats.json")
}
