package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OrtEnvironment
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real step graph through onnxruntime (desktop jar, same Java API as onnxruntime-android): decode the 50 parity
 * messages and compare with the Python decode on the same int8 graph. Needs AC_MODEL_DIR with lm_block4.step.int8.ort
 * (what the app downloads); CI fetches it.
 */
class OrtParityTest {
    private val dir = System.getenv("AC_MODEL_DIR").orEmpty()
    private val meta = StepMeta(nKvHeads = 2, headDim = 64, convKernel = 7, dModel = 384,
        stateNames = (0 until 5).flatMap { listOf("k$it", "v$it") } + (0 until 9).map { "c$it" })

    /** the ORT-format graph if present, else the web demo's .onnx of the same model */
    private fun open(name0: String): StepModel {
        val name = if (File(dir, name0).exists()) name0 else name0.replace(".ort", ".onnx")
        val f = File(dir, name)
        assumeTrue("no $f (set AC_MODEL_DIR)", dir.isNotEmpty() && f.exists())
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val t0 = System.nanoTime()
        val m = StepModel.open(OrtEnvironment.getEnvironment(), buf, meta, threads = 2, ortFormat = name.endsWith(".ort"))
        println("$name: session ready in ${(System.nanoTime() - t0) / 1e6} ms")
        return m
    }

    @Test fun block4MatchesPython() {
        val step = open("lm_block4.step.int8.ort")
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val p = PipelineTest.params(ref, "lm_block4")
        val exp = PipelineTest.outs(ref, "lm_block4")
        val msgs = PipelineTest.messages(ref)
        var same = 0
        var words = 0
        var wsame = 0
        val t0 = System.nanoTime()
        for ((i, m) in msgs.withIndex()) {
            val sc = NeuralScorer(Fixtures.tokM1, step)   // fresh caches per message, like the reference
            val got = Decoder.decode(m, sc, p).outs
            if (got == exp[i]) same++ else println("message $i differs:\n  exp ${exp[i]}\n  got $got")
            words += got.size
            wsame += got.zip(exp[i]).count { (a, b) -> a == b }
        }
        println("lm_block4 .ort vs Python int8: $same/${msgs.size} messages, $wsame/$words words identical, ${(System.nanoTime() - t0) / 1e9}s")
        // int8 dynamic-quant kernels differ between CPUs (x86 VNNI vs ARM dotprod): near-ties may flip; allow 1%
        assertTrue((words - wsame) * 100 <= words, "too many word differences: ${words - wsame}/$words")
        step.close()
    }

    /**
     * Cached-prefix extension gives the same scores as a fresh full-context run, up to the int8 graph's dynamic
     * activation quantization (scale per call, so splitting the context into two runs moves scores by ~0.1 nat).
     */
    @Test fun prefixExtensionMatchesFreshRun() {
        val step = open("lm_block4.step.int8.ort")
        val tok = Fixtures.tokM1
        val left1 = "hey are you coming to the"
        val left2 = "$left1 party tonight or"
        val reqs = listOf("not", "nit", "now").map { LmReq(left2, " $it") }
        val fresh = NeuralScorer(tok, step).score(reqs)
        val warm = NeuralScorer(tok, step)
        warm.warm(left1)
        val before = warm.tokensRun
        val ext = warm.score(reqs)
        println("extension ran ${warm.tokensRun - before} tokens; fresh ${fresh.toList()} ext ${ext.toList()}")
        for (i in reqs.indices) assertEquals(fresh[i], ext[i], 1.0)
        assertEquals(fresh.indices.maxBy { fresh[it] }, ext.indices.maxBy { ext[it] })
        // style token changes the context but still scores
        val styled = NeuralScorer(tok, step, styleId = 250).score(reqs)
        assertTrue(styled.all { it.isFinite() })
        step.close()
    }
}
