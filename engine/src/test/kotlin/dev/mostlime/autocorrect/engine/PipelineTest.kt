package dev.mostlime.autocorrect.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals

/** Python decode() replayed: every score() call answered from the recorded log -> identical outputs. */
class PipelineTest {
    companion object {
        fun key(reqs: List<LmReq>) = reqs.joinToString("\u0002") { it.left + "\u0001" + it.cont }

        fun messages(ref: JsonObject): List<Message> {
            val texts = ref["texts"]!!.jsonArray.map { it.jsonPrimitive.content }
            val cands = ref["cands"]!!.jsonArray
            return texts.mapIndexed { m, t ->
                val (seps, words) = segment(t)
                Message(seps, words.mapIndexed { i, w ->
                    val e = cands[m].jsonArray[i].jsonObject
                    Slot(w, e["cands"]!!.jsonArray.map { it.jsonPrimitive.content },
                        e["sp"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(), e["typed_idx"]!!.jsonPrimitive.int)
                })
            }
        }

        fun params(ref: JsonObject, model: String): DecodeParams {
            val e = ref["params"]!!.jsonObject[model]!!.jsonObject["e"]!!.jsonObject
            return DecodeParams(e["lam"]!!.jsonPrimitive.double, e["bias"]!!.jsonPrimitive.double,
                e["margin"]?.jsonPrimitive?.doubleOrNull, e["lam_r"]?.jsonPrimitive?.doubleOrNull)
        }

        fun outs(ref: JsonObject, model: String): List<List<String>> =
            ref["outs"]!!.jsonObject[model]!!.jsonArray.map { o -> o.jsonArray.map { it.jsonPrimitive.content } }
    }

    @Test fun replayMatchesPythonDecode() {
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val rep = Fixtures.json("replay_lm_base.json.gz").jsonObject
        val log = HashMap<String, DoubleArray>()
        for ((k, v) in rep["log"]!!.jsonObject) {
            val reqs = Json.parseToJsonElement(k).jsonArray.map { r -> r.jsonArray.let { LmReq(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content) } }
            log[key(reqs)] = v.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        }
        val scorer = LmScorer { reqs -> log[key(reqs)] ?: error("unrecorded request ${reqs.take(2)}") }
        val p = params(ref, "lm_base")
        val exp = rep["outs"]!!.jsonArray.map { o -> o.jsonArray.map { it.jsonPrimitive.content } }
        val msgs = messages(ref)
        var words = 0
        for ((i, m) in msgs.withIndex()) {
            assertEquals(exp[i], Decoder.decode(m, scorer, p).outs, "message $i")
            words += m.words.size
        }
        println("replay: ${msgs.size} messages / $words words identical")
    }

    /** The keyboard's word-at-a-time API with the battery knobs off makes the same choices as decode(). */
    @Test fun correctorEqualsDecodeWithKnobsOff() {
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val rep = Fixtures.json("replay_lm_base.json.gz").jsonObject
        val log = HashMap<String, DoubleArray>()
        // index single requests so subsets of a recorded call can be answered
        val single = HashMap<String, Double>()
        for ((k, v) in rep["log"]!!.jsonObject) {
            val reqs = Json.parseToJsonElement(k).jsonArray.map { r -> r.jsonArray.let { LmReq(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content) } }
            val vals = v.jsonArray.map { it.jsonPrimitive.double }
            log[key(reqs)] = vals.toDoubleArray()
            reqs.forEachIndexed { i, r -> single[key(listOf(r))] = vals[i] }
        }
        // whole recorded batch when possible (batch padding changes the floats slightly), else per request
        val scorer = LmScorer { reqs -> log[key(reqs)] ?: DoubleArray(reqs.size) { single[key(listOf(reqs[it]))] ?: error("unrecorded ${reqs[it]}") } }
        val p = params(ref, "lm_base")
        val exp = rep["outs"]!!.jsonArray.map { o -> o.jsonArray.map { it.jsonPrimitive.content } }
        val msgs = messages(ref)
        val corr = Corrector(Fixtures.gen, null, CorrectorConfig(lmTopK = 0, skipMargin = Double.POSITIVE_INFINITY))
        for ((mi, m) in msgs.withIndex()) {
            val outs = ArrayList<String>()
            var prev: PrevWord? = null
            for ((t, w) in m.words.withIndex()) {
                val left = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                val r = corr.decideSlot(w, left, m.seps[t], scorer, p, prev)
                outs.add(r.out)
                r.recheck?.let { rc -> outs[t - 1] = rc.to }
                // the re-check may have changed word t-1: the next re-check needs the current text
                val leftNow = buildString { for (i in 0 until t) append(m.seps[i]).append(outs[i]) }
                prev = PrevWord(r.slot, r.chosen, leftNow, m.seps[t], true)
            }
            assertEquals(exp[mi], outs, "message $mi")
        }
    }
}
