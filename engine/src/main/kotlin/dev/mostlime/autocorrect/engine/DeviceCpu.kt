package dev.mostlime.autocorrect.engine

import java.io.File

/**
 * ORT intra-op thread count from the device's core layout.
 *
 * One LM call is a short burst of ~100 small parallel sections, and each section waits for its slowest thread. On a
 * big.LITTLE phone a thread that lands on an in-order little core (A53/A55/A510/A520, Kryo Silver) holds the big cores
 * back, so use only the big cores (at most 4: beyond that the sections are too small to split). On an all-little SoC
 * (e.g. Helio P35 / Galaxy A12: 8x A53 in a 2.3 GHz and a 1.8 GHz cluster) there is nothing to wait for, so use the
 * fast cluster, 4 threads. Core type comes from /proc/cpuinfo "CPU part" per core, falling back to max-frequency
 * clusters when that is missing.
 */
object DeviceCpu {
    class Core(val id: Int, val part: Int?, val maxKHz: Long?)

    /** in-order little cores: (implementer << 12) | part */
    private val LITTLE = setOf(
        0x41d03, 0x41d04, 0x41d05, 0x41d46, 0x41d80, 0x41d01,   // Arm Cortex-A53, A35, A55, A510, A520, A32
        0x51801, 0x51803, 0x51805,                               // Qualcomm Kryo 2xx/3xx/4xx Silver
    )

    fun isLittle(c: Core): Boolean = c.part != null && c.part in LITTLE

    /** pure policy (unit-testable): cores -> intra-op threads */
    fun threadsFor(cores: List<Core>, cap: Int = 4): Int {
        if (cores.isEmpty()) return 2
        val typed = cores.all { it.part != null }
        val big: Int = if (typed) {
            cores.count { !isLittle(it) }
        } else {
            // no part ids: cores within 15% of the top frequency count as big, if that is a real minority
            val f = cores.mapNotNull { it.maxKHz }
            if (f.size != cores.size) return minOf(cap, cores.size).coerceAtLeast(1)
            val top = f.max()
            val n = f.count { it >= top * 0.85 }
            if (n == cores.size) 0 else n
        }
        return when {
            big >= 2 -> minOf(cap, big)
            // one big core (rare 1+N designs): it plus one helper
            big == 1 -> 2
            // all little: the fastest cluster (or all cores if the clusters can't be told apart)
            else -> {
                val f = cores.mapNotNull { it.maxKHz }
                val fast = if (f.size == cores.size) f.count { it == f.max() } else cores.size
                minOf(cap, maxOf(2, fast))
            }
        }
    }

    /** cores of this device (empty when /proc and /sys can't be read, e.g. on a desktop JVM without them) */
    fun cores(): List<Core> = runCatching {
        val n = Runtime.getRuntime().availableProcessors()
        val parts = HashMap<Int, Int>()
        var cur = -1; var impl = -1
        File("/proc/cpuinfo").forEachLine { l ->
            val k = l.substringBefore(':').trim(); val v = l.substringAfter(':', "").trim()
            when (k) {
                "processor" -> { cur = v.toIntOrNull() ?: -1; impl = -1 }
                "CPU implementer" -> impl = v.removePrefix("0x").toIntOrNull(16) ?: -1
                "CPU part" -> { val p = v.removePrefix("0x").toIntOrNull(16); if (cur >= 0 && impl >= 0 && p != null) parts[cur] = (impl shl 12) or p }
            }
        }
        val ids = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.map { it.name.removePrefix("cpu").toInt() }?.sorted()?.takeIf { it.isNotEmpty() } ?: (0 until n).toList()
        ids.map { i ->
            val f = runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            Core(i, parts[i], f)
        }
    }.getOrDefault(emptyList())

    fun intraOpThreads(): Int {
        val c = cores()
        return if (c.isEmpty()) minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1) else threadsFor(c)
    }

    fun describe(): String = cores().joinToString(" ") { c ->
        "${c.id}:${c.part?.let { "%x".format(it) } ?: "?"}${if (isLittle(c)) "L" else ""}@${c.maxKHz?.div(1000) ?: "?"}"
    }
}
