package dev.mostlime.autocorrect

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Is this a low-end phone (default to the Lite model there)? */
object Device {
    /** ARM little / in-order cores (CPU part in /proc/cpuinfo): A53, A35, A55, A510, A520, A32, A7 */
    private val LITTLE = setOf("0xd03", "0xd04", "0xd05", "0xd46", "0xd80", "0xd01", "0xc07")

    @Volatile private var cached: Boolean? = null

    /** low RAM (system flag or < 4.5 GB) or no big (A7x-class or newer) cores */
    fun lowEnd(ctx: Context): Boolean = cached ?: compute(ctx).also { cached = it }

    fun reason(ctx: Context): String? {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return when {
            am.isLowRamDevice -> "this phone is marked low-RAM"
            mi.totalMem < 4_500_000_000L -> "this phone has %.1f GB of RAM".format(mi.totalMem / 1e9)
            !hasBigCores() -> "this phone has only small CPU cores"
            else -> null
        }
    }

    private fun compute(ctx: Context) = runCatching { reason(ctx) != null }.getOrDefault(false)

    fun hasBigCores(): Boolean = runCatching {
        val parts = File("/proc/cpuinfo").readLines().filter { it.startsWith("CPU part") }.map { it.substringAfter(':').trim().lowercase() }
        parts.isEmpty() || parts.any { it !in LITTLE }
    }.getOrDefault(true)
}
