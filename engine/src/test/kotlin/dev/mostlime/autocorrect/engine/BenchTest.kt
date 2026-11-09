package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OrtEnvironment
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Keyboard-style latency on this machine: word-at-a-time Corrector (top-K, skip, re-check) over the parity messages.
 * AC_BENCH=1 AC_MODEL_DIR=<dir with m2.step.int4m.ort> [AC_GRAPH=file] [AC_THREADS=n] [AC_SPIN=1] [AC_CFG="k=v,k=v"]
 *   [AC_MSGS=n] [AC_IDLE=1] [AC_SWIPE=n] [AC_DUMP=decisions.txt] ./gradlew :engine:test --tests '*BenchTest*'
 * AC_DUMP writes every decision (typing outputs, idle fixes, swipe order) so two graphs can be diffed.
 */
class BenchTest {
    @Test fun bench() {
        assumeTrue(System.getenv("AC_BENCH") == "1")
        val dir = System.getenv("AC_MODEL_DIR").orEmpty()
        val name = System.getenv("AC_GRAPH") ?: "m2.step.int4m.ort"
        val f = File(dir, name)
        assumeTrue(f.exists())
        // m2 / m2s (106M) or the 34M block4 ("lm_block4*")
        val meta = if (name.startsWith("lm_block4")) StepMeta(2, 64, 7, 384, (0 until 5).flatMap { listOf("k$it", "v$it") } + (0 until 9).map { "c$it" })
            else StepMeta(2, 64, 7, 640, (0 until 6).flatMap { listOf("k$it", "v$it") } + (0 until 10).map { "c$it" })
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val threads = System.getenv("AC_THREADS")?.toInt() ?: 2
        val cfg = System.getenv("AC_CFG").orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val t0 = System.nanoTime()
        val spin = System.getenv("AC_SPIN") == "1"
        val step = StepModel.open(OrtEnvironment.getEnvironment(), buf, meta, threads = threads, ortFormat = name.endsWith(".ort"),
            extraConfig = cfg, spinning = spin)
        println("session ${(System.nanoTime() - t0) / 1e6} ms, $name, tree ${step.isTree}, threads $threads, spin $spin, cfg $cfg")
        val tok = if (name.startsWith("lm_block4")) Fixtures.tokM1 else Fixtures.tokM2
        val dump = System.getenv("AC_DUMP")?.let { java.io.PrintWriter(File(it)) }
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
                dump?.println("w\t${w.typed}\t${r.out}\t${r.recheck?.to ?: ""}")
                val leftNow = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                prev = PrevWord(r.slot, r.chosen, leftNow, m.seps[t], r.lmScored, if (r.lmScored) r.margin else 0.0,
                    if (r.lmScored && System.getenv("AC_FULL_RECHECK") == null) r.scores else null)
            }
        }
        val c = step.counters
        val runs = c.runs - c0.runs
        println("words $words, LM words $lmWords (${100 * lmWords / words}%%), LM ms/word %.1f, cand-gen ms/word %.1f".format(lmMs / lmWords, candMs / words))
        println("runs/LM word %.2f, run ms %.2f (ort %.2f, java %.2f), padded cells/run %.1f, used tokens/run %.1f, head rows/run %.1f".format(
            runs.toDouble() / lmWords, (c.totalNs - c0.totalNs) / 1e6 / runs, (c.ortNs - c0.ortNs) / 1e6 / runs,
            ((c.totalNs - c0.totalNs) - (c.ortNs - c0.ortNs)) / 1e6 / runs, (c.cells - c0.cells).toDouble() / runs, (c.usedTokens - c0.usedTokens).toDouble() / runs,
            (c.headRows - c0.headRows).toDouble() / runs))
        // idle passes (run after a typing pause, never in the typing path): whole-sentence suggest check and far rewind
        if (System.getenv("AC_IDLE") == "1") {
            var sMs = 0.0; var sWords = 0; var sents = 0; var rMs = 0.0; var rTargets = 0
            for (m in msgs) {
                val text = m.words.indices.joinToString("") { m.seps[it] + m.words[it].typed }
                for (r in SentenceCheck.split(text)) {
                    val sc = NeuralScorer(tok, step)
                    val req = SentenceReq(text.substring(0, r.first).takeLast(200), text.substring(r))
                    val t1 = System.nanoTime()
                    val fx = SentenceCheck.check(corr, req, sc, p, 1.5)
                    fx.forEach { dump?.println("s\t${it.start}\t${it.from}\t${it.to}\t${it.fixes}") }
                    sMs += (System.nanoTime() - t1) / 1e6; sWords += SEG.findAll(req.sentence).count(); sents++
                    // far rewind over the same sentence: confusables + commas (formal)
                    val ws = SEG.findAll(req.sentence).toList().takeLast(Rewind.MAX_SENTENCE_WORDS)
                    val targets = ArrayList<RewindTarget>()
                    for (j in 0 until ws.size - 1) {
                        val w = ws[j]
                        val left = req.left + req.sentence.substring(0, w.range.first).trimEnd()
                        val rest = req.sentence.substring(w.range.last + 1)
                        if (Rewind.isTarget(w.value)) targets.add(RewindTarget(w.range.first, w.value, left, " ", rest, ws.size - 1 - j))
                        targets.add(RewindTarget(w.range.first, w.value, left, " ", rest, ws.size - 1 - j, RewindTarget.COMMA))
                    }
                    val t2 = System.nanoTime()
                    val rw = Rewind.check(targets, sc, 1.25, pen = mapOf("comma" to 0.0, "punct" to 3.0))
                    rw.forEach { dump?.println("r\t${it.start}\t${it.from}\t${it.to}") }
                    rMs += (System.nanoTime() - t2) / 1e6; rTargets += targets.size
                }
            }
            println("idle suggest check: %d sentences, %.0f ms/sentence, %.1f ms/word".format(sents, sMs / sents, sMs / sWords))
            println("idle far rewind (formal, confusables + commas): %.0f ms/sentence, %.1f ms/target".format(rMs / sents, rMs / maxOf(1, rTargets)))
        }
        // swipe: synthetic swipes (swipe_ref.json) after real message contexts, LM rerank of the top-K in one call
        val nSwipe = System.getenv("AC_SWIPE")?.toInt() ?: 0
        if (nSwipe > 0) {
            val dec = SwipeDecoder(Lexicon(File("../app/src/main/assets/lexicon.tsv").readText()))
            val cases = Fixtures.json("swipe_ref.json").jsonObject["cases"]!!.jsonArray.take(nSwipe)
            val sc = NeuralScorer(tok, step)
            var lm = 0.0; var geo = 0.0; var n = 0; var gold = 0
            for ((i, e) in cases.withIndex()) {
                val o = e.jsonObject
                val path = o["path"]!!.jsonArray.map { q -> Touch(q.jsonArray[0].jsonPrimitive.double, q.jsonArray[1].jsonPrimitive.double) }
                val m = msgs[i % msgs.size]
                val left = m.words.take(1 + i % maxOf(1, m.words.size - 1)).withIndex().joinToString("") { (j, w) -> m.seps[j] + w.typed }
                val r = dec.decodeAndRerank(path, left, " ", "", sc)
                if (r.lm != null) { lm += r.lmMs; n++ }
                geo += r.decodeMs
                if (r.best.equals(o["gold"]!!.jsonPrimitive.content, ignoreCase = true)) gold++
                dump?.println("sw\t${o["gold"]!!.jsonPrimitive.content}\t${r.ranked(3)}")
            }
            println("swipe: %d swipes, LM rerank %.1f ms/swipe (%d cands), decode %.1f ms/swipe, top-1 = gold %d".format(
                cases.size, lm / maxOf(1, n), 20, geo / cases.size, gold))
        }
        dump?.close()
        step.close()
    }

    /**
     * Typing latency vs context length on one long text (the parity messages one after another, ~2k words): the left
     * context is the keyboard's window (maxCtx * 5 chars, its start moved in quarter-window chunks like the controller)
     * and the scorer clips to maxCtx tokens in chunks. ms per LM-scored word, avg and p95, over the words typed after
     * the first 300 (window full).
     * AC_BENCH_CTX="64,256,1024" AC_MODEL_DIR=<dir> [AC_GRAPH=m2s.step.int4t.ort] [AC_THREADS=n] [AC_WORDS=n]
     */
    @Test fun benchContext() {
        val ctxs = System.getenv("AC_BENCH_CTX")?.split(',')?.map { it.trim().toInt() } ?: emptyList()
        assumeTrue(ctxs.isNotEmpty())
        val dir = System.getenv("AC_MODEL_DIR").orEmpty()
        val name = System.getenv("AC_GRAPH") ?: "m2s.step.int4t.ort"
        val f = File(dir, name)
        assumeTrue(f.exists())
        val meta = if (name.startsWith("lm_block4")) StepMeta(2, 64, 7, 384, (0 until 5).flatMap { listOf("k$it", "v$it") } + (0 until 9).map { "c$it" })
            else StepMeta(2, 64, 7, 640, (0 until 6).flatMap { listOf("k$it", "v$it") } + (0 until 10).map { "c$it" })
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val threads = System.getenv("AC_THREADS")?.toInt() ?: 2
        val step = StepModel.open(OrtEnvironment.getEnvironment(), buf, meta, threads = threads, ortFormat = true)
        val tok = if (name.startsWith("lm_block4")) Fixtures.tokM1 else Fixtures.tokM2
        val corr = Corrector(Fixtures.gen, Fixtures.gen2)
        val p = DecodeParams(1.25, 0.0, 0.0, 1.25)
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val msgs = PipelineTest.messages(ref)
        val words = msgs.flatMap { m -> m.words.map { it.typed } }
        val nWords = minOf(words.size, System.getenv("AC_WORDS")?.toInt() ?: 420)
        println("context bench: $name, tree ${step.isTree}, threads $threads, ${words.size} words available, typing $nWords")
        for (ctx in ctxs) {
            val sc = NeuralScorer(tok, step).also { it.maxCtx = ctx }
            val charWindow = maxOf(400, ctx * 5)
            val chunk = maxOf(64, charWindow / 4)
            val text = StringBuilder()
            val ms = ArrayList<Double>()
            var prev: PrevWord? = null
            for (i in 0 until nWords) {
                val w = words[i]
                val sep = if (text.isEmpty()) "" else " "
                val cp = text.length
                val start = maxOf(0, ((cp - charWindow + chunk - 1) / chunk) * chunk)
                val left = text.substring(start)
                val t0 = System.nanoTime()
                val r = corr.correctWord(WordInput(w, null, left, sep), sc, p, prev)
                val dt = (System.nanoTime() - t0) / 1e6
                if (i >= maxOf(300, nWords - 300) && r.lmScored) ms.add(dt)
                text.append(sep).append(r.out)
                prev = PrevWord(r.slot, r.chosen, left, sep, r.lmScored, if (r.lmScored) r.margin else 0.0, if (r.lmScored) r.scores else null)
            }
            ms.sort()
            val avg = ms.average(); val p95 = ms[(ms.size * 0.95).toInt().coerceAtMost(ms.size - 1)]
            println("ctx %4d tokens (window %d chars): %.1f ms/word avg, p95 %.1f ms, %d LM words, last ctx %d tokens".format(
                ctx, charWindow, avg, p95, ms.size, sc.lastCtxTokens))
        }
        step.close()
    }
}
