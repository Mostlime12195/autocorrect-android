package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OrtEnvironment
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Scoring a big candidate set in chunks of 32 rows gives bit-identical scores (int4 graph: rows are independent) and a
 * much lower peak than one big batch. Needs AC_MODEL_DIR with m2s.step.int4m.ort (CI fetches it; skipped without).
 */
class MemoryTest {
    private val dir = System.getenv("AC_MODEL_DIR").orEmpty()
    private val meta = StepMeta(2, 64, 7, 640, (0 until 6).flatMap { listOf("k$it", "v$it") } + (0 until 10).map { "c$it" })

    private fun rssBytes(): Long = try {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() * 1024
    } catch (e: Exception) { 0L }

    /** highest resident size sampled while [body] runs, minus the level before it */
    private fun peakGrowth(body: () -> Unit): Long {
        val base = rssBytes()
        @Volatile var peak = base
        @Volatile var stop = false
        val t = Thread { while (!stop) { peak = maxOf(peak, rssBytes()); Thread.sleep(1) } }
        t.start()
        body()
        stop = true; t.join()
        return maxOf(peak, rssBytes()) - base
    }

    @Test fun chunkedScoringIsIdenticalAndLighter() {
        val f = File(dir, "m2s.step.int4m.ort")
        assumeTrue("no $f (set AC_MODEL_DIR)", dir.isNotEmpty() && f.exists() && File("/proc/self/status").exists())
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val step = StepModel.open(OrtEnvironment.getEnvironment(), buf, meta, threads = 4, ortFormat = true)
        val left = "hey are you coming to the party tonight or are you staying home because i think everyone else is going and"
        val words = listOf("not", "now", "nit", "know", "note", "noted", "nothing", "never", "new", "next", "nice", "night",
            "nine", "no", "yes", "yeah", "yep", "maybe", "might", "mind", "mine", "miss", "missed", "more", "most", "much",
            "must", "my", "the", "then", "there", "their", "they", "them", "this", "that", "those", "though", "through", "thought")
        val reqs = words.flatMap { w -> listOf("", "s", "ed", "ing", "er").map { LmReq(left, " $w$it") } } +
            words.map { LmReq(left, " $it.") }
        assertTrue(reqs.size >= 200)
        fun score(rows: Int): DoubleArray {
            val sc = NeuralScorer(Fixtures.tokM2, step, styleId = 250)
            step.rowLimit = rows
            return sc.score(reqs)
        }
        score(32)   // warm-up
        var chunked = DoubleArray(0)
        var whole = DoubleArray(0)
        val gChunked = peakGrowth { chunked = score(32) }
        val gWhole = peakGrowth { whole = score(Int.MAX_VALUE) }
        val tiny = score(5)
        println("peak growth over ${reqs.size} candidates: chunks of 32 ${gChunked shr 20} MB, one batch ${gWhole shr 20} MB; " +
            "identical: ${chunked.contentEquals(whole) && chunked.contentEquals(tiny)}")
        assertContentEquals(whole, chunked, "chunked scores differ from one big batch")
        assertContentEquals(whole, tiny, "chunk size 5 differs")
        assertTrue(chunked.all { it.isFinite() })
        assertTrue(gChunked < gWhole * 0.7, "chunking should cut the peak: $gChunked vs $gWhole")
        step.close()
    }
}
