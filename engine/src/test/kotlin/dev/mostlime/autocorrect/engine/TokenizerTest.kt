package dev.mostlime.autocorrect.engine

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals

class TokenizerTest {
    private fun check(tok: SPTokenizer, cases: String) {
        val c = Fixtures.json(cases).jsonObject
        var n = 0
        for (e in c["encode"]!!.jsonArray) {
            val o = e.jsonObject
            val text = o["text"]!!.jsonPrimitive.content
            val ids = o["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(ids, tok.encode(text).toList(), "encode ${text.take(60)}")
            assertEquals(o["decoded"]!!.jsonPrimitive.content, tok.decode(ids.toIntArray()), "decode(encode) ${text.take(60)}")
            n++
        }
        for (e in c["decode"]!!.jsonArray) {
            val o = e.jsonObject
            val ids = o["ids"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            assertEquals(o["text"]!!.jsonPrimitive.content, tok.decode(ids), "decode ${ids.toList()}")
            n++
        }
        println("$cases: $n cases ok")
    }

    @Test fun m1() = check(Fixtures.tokM1, "tok_m1.json")
    @Test fun m2() = check(Fixtures.tokM2, "tok_m2.json")

    @Test fun styleBytePiecesNeverInText() {
        // style.py: <0xF5>-<0xF7> (ids 249-251 in the m2 vocab) are byte pieces that valid UTF-8 never produces
        val tok = Fixtures.tokM2
        assertEquals(listOf("<0xF5>", "<0xF6>", "<0xF7>"), (249..251).map { tok.pieces[it] })
        for (s in listOf("hello there", "café 😀", "õö÷")) assert(tok.encode(s).none { it in 249..251 })
    }
}
