package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OrtEnvironment
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.assertTrue

/**
 * Local model checks on the real m2 graphs (skipped unless AC_STYLE_CHECK=1 and AC_MODEL_DIR has the .ort files):
 * - m2s (style tokens) decodes the parity messages close to m2 with each style token, i.e. the prefix is valid
 * - the re-check pass on the plausible subset (prev.scores) makes the same choices as the full re-check
 */
class StyleModelTest {
    private val dir = System.getenv("AC_MODEL_DIR").orEmpty()
    private val meta = StepMeta(2, 64, 7, 640, (0 until 6).flatMap { listOf("k$it", "v$it") } + (0 until 10).map { "c$it" })
    private val p = DecodeParams(1.25, 0.0, 0.0, 1.25)

    private fun open(name: String): StepModel {
        val f = File(dir, name)
        assumeTrue("no $f", System.getenv("AC_STYLE_CHECK") == "1" && f.exists())
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        return StepModel.open(OrtEnvironment.getEnvironment(), buf, meta, threads = 2, ortFormat = true)
    }

    private fun agree(a: List<List<String>>, b: List<List<String>>) =
        a.zip(b).sumOf { (x, y) -> x.zip(y).count { (u, v) -> u == v } }.toDouble() / a.sumOf { it.size }

    @Test fun m2sStyleTokensDecode() {
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val msgs = PipelineTest.messages(ref).take(System.getenv("AC_MSGS")?.toInt() ?: 50)
        val typed = msgs.map { m -> m.words.map { it.typed } }
        val m2 = open("m2.step.int4m.ort")
        val base = msgs.map { Decoder.decode(it, NeuralScorer(Fixtures.tokM2, m2), p).outs }
        m2.close()
        val m2s = open("m2s.step.int4m.ort")
        val styles = linkedMapOf<String, Int?>("none" to null, "casual" to 249, "standard" to 250, "formal" to 251)
        val outs = styles.mapValues { (_, id) -> msgs.map { Decoder.decode(it, NeuralScorer(Fixtures.tokM2, m2s, styleId = id), p).outs } }
        m2s.close()
        println("m2 vs typed: %.3f".format(agree(base, typed)))
        for ((s, o) in outs) println("m2s[$s] vs m2: %.3f, vs typed: %.3f".format(agree(o, base), agree(o, typed)))
        for (i in 0 until 6) {
            println("typed    ${typed[i].joinToString(" ")}")
            println("m2       ${base[i].joinToString(" ")}")
            for ((s, o) in outs) println("m2s %-8s ${o[i].joinToString(" ")}".format(s))
        }
        for ((s, o) in outs) assertTrue(agree(o, base) >= 0.9, "m2s[$s] too far from m2")
    }

    @Test fun recheckSubsetKeepsChoices() {
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val msgs = PipelineTest.messages(ref).take(System.getenv("AC_MSGS")?.toInt() ?: 50)
        val step = open("m2.step.int4m.ort")
        val corr = Corrector(Fixtures.gen, Fixtures.gen2)
        fun run(subset: Boolean): Pair<List<List<String>>, Double> {
            var ms = 0.0
            val res = msgs.map { m ->
                val sc = NeuralScorer(Fixtures.tokM2, step)
                val outs = ArrayList<String>()
                var prev: PrevWord? = null
                for ((t, w) in m.words.withIndex()) {
                    val left = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                    val r = corr.correctWord(WordInput(w.typed, null, left, m.seps[t]), sc, p, prev)
                    if (r.lmScored) ms += r.lmMs
                    outs.add(r.out)
                    r.recheck?.let { outs[t - 1] = it.to }
                    val leftNow = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                    prev = PrevWord(r.slot, r.chosen, leftNow, m.seps[t], r.lmScored, if (r.lmScored) r.margin else 0.0,
                        if (r.lmScored && subset) r.scores else null)
                }
                outs
            }
            return res to ms
        }
        run(true)   // warm up
        val (full, msFull) = run(false)
        val (sub, msSub) = run(true)
        step.close()
        val diff = full.zip(sub).sumOf { (a, b) -> a.zip(b).count { (x, y) -> x != y } }
        full.zip(sub).forEachIndexed { i, (a, b) -> if (a != b) println("message $i:\n  full $a\n  sub  $b") }
        println("re-check subset: $diff words differ of ${full.sumOf { it.size }}; LM ms full %.0f, subset %.0f".format(msFull, msSub))
        assertTrue(diff * 200 <= full.sumOf { it.size }, "subset re-check changed too many words: $diff")
    }
}
