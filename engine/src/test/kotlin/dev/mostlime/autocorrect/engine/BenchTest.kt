package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OrtEnvironment
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Keyboard-style latency on this machine: word-at-a-time Corrector (top-K, skip, re-check) over the parity messages.
 * AC_BENCH=1 AC_MODEL_DIR=<dir with m2.step.int4m.ort> [AC_THREADS=n] [AC_CFG="k=v,k=v"] ./gradlew :engine:test --tests '*BenchTest*'
 */
class BenchTest {
    @Test fun bench() {
        assumeTrue(System.getenv("AC_BENCH") == "1")
        val dir = System.getenv("AC_MODEL_DIR").orEmpty()
        val name = System.getenv("AC_GRAPH") ?: "m2.step.int4m.ort"
        val f = File(dir, name)
        assumeTrue(f.exists())
        val meta = StepMeta(2, 64, 7, 640, (0 until 6).flatMap { listOf("k$it", "v$it") } + (0 until 10).map { "c$it" })
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val threads = System.getenv("AC_THREADS")?.toInt() ?: 2
        val cfg = System.getenv("AC_CFG").orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val t0 = System.nanoTime()
        val step = StepModel.open(OrtEnvironment.getEnvironment(), buf, meta, threads = threads, ortFormat = name.endsWith(".ort"), extraConfig = cfg)
        println("session ${(System.nanoTime() - t0) / 1e6} ms, threads $threads, cfg $cfg")
        val tok = Fixtures.tokM2
        val corr = Corrector(Fixtures.gen, Fixtures.gen2)
        val p = DecodeParams(1.25, 0.0, 0.0, 1.25, mapOf("split" to 2.0, "space" to 3.0, "contr" to 1.0, "contr_rw" to 0.0, "gdrop" to 1.0, "merge" to 4.0))
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val msgs = PipelineTest.messages(ref).take(System.getenv("AC_MSGS")?.toInt() ?: 12)
        var lmWords = 0; var lmMs = 0.0; var candMs = 0.0; var words = 0
        val warm = NeuralScorer(tok, step)
        corr.correctWord(WordInput("teh", null, "warm up the", " "), warm, p, null)
        val c0 = StepModel.Counters().also { it.runs = step.counters.runs; it.totalNs = step.counters.totalNs; it.ortNs = step.counters.ortNs; it.cells = step.counters.cells; it.usedTokens = step.counters.usedTokens }
        for (m in msgs) {
            val sc = NeuralScorer(tok, step)
            val outs = ArrayList<String>()
            var prev: PrevWord? = null
            for ((t, w) in m.words.withIndex()) {
                val left = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                val tc = System.nanoTime()
                corr.candidates(w.typed, null)
                candMs += (System.nanoTime() - tc) / 1e6
                val r = corr.correctWord(WordInput(w.typed, null, left, m.seps[t]), sc, p, prev)
                words++
                if (r.lmScored) { lmWords++; lmMs += r.lmMs }
                outs.add(r.out)
                r.recheck?.let { outs[t - 1] = it.to }
                val leftNow = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                prev = PrevWord(r.slot, r.chosen, leftNow, m.seps[t], r.lmScored, if (r.lmScored) r.margin else 0.0,
                    if (r.lmScored && System.getenv("AC_FULL_RECHECK") == null) r.scores else null)
            }
        }
        val c = step.counters
        val runs = c.runs - c0.runs
        println("words $words, LM words $lmWords (${100 * lmWords / words}%%), LM ms/word %.1f, cand-gen ms/word %.1f".format(lmMs / lmWords, candMs / words))
        println("runs/LM word %.2f, run ms %.2f (ort %.2f, java %.2f), padded cells/run %.1f, used tokens/run %.1f".format(
            runs.toDouble() / lmWords, (c.totalNs - c0.totalNs) / 1e6 / runs, (c.ortNs - c0.ortNs) / 1e6 / runs,
            ((c.totalNs - c0.totalNs) - (c.ortNs - c0.ortNs)) / 1e6 / runs, (c.cells - c0.cells).toDouble() / runs, (c.usedTokens - c0.usedTokens).toDouble() / runs))
        step.close()
    }
}
