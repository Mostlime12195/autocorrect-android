package dev.mostlime.autocorrect.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.zip.GZIPInputStream

object Fixtures {
    fun text(name: String): String {
        val s = Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        return (if (name.endsWith(".gz")) GZIPInputStream(s) else s).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))

    val lexicon: Lexicon by lazy {
        val f = listOf("../app/src/main/assets/lexicon.tsv", "app/src/main/assets/lexicon.tsv").map(::File).first { it.exists() }
        Lexicon(f.readText())
    }
    val gen: CandidateGenerator by lazy { CandidateGenerator(lexicon, SpatialModel(sigma = 0.3)) }
    val gen2: CandidateGeneratorV2 by lazy { CandidateGeneratorV2(gen) }
    val tokM1: SPTokenizer by lazy { SPTokenizer.fromJson(text("vocab.json")) }
    val tokM2: SPTokenizer by lazy { SPTokenizer.fromJson(text("vocab_m2.json")) }
}
