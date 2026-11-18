package com.riziuzi.utho.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The on-device meaning model: one small vector per word (English, Hindi, Hinglish and subject terms),
 * distilled from a multilingual AI sentence model by tools/meaning/build.py. A note's meaning is the
 * weighted average of its words' vectors, so notes about the same thing point the same way even when
 * they share no words ("political parties" ~ polity). Works offline, in well under a millisecond.
 */
class Meaning private constructor(
    private val index: HashMap<String, Int>,
    val dims: Int,
    private val scales: FloatArray,
    private val weights: FloatArray,
    private val data: ByteArray,
) {
    val size: Int get() = index.size

    private fun lookup(t: String): Int? {
        index[t]?.let { return it }
        if (t.length > 4 && !Phonetic.hasDevanagari(t)) {
            if (t.endsWith("ies")) index[t.dropLast(3) + "y"]?.let { return it }
            if (t.endsWith("es")) index[t.dropLast(2)]?.let { return it }
            if (t.endsWith("s")) index[t.dropLast(1)]?.let { return it }
        }
        return null
    }

    fun knows(word: String): Boolean = lookup(TopicClassifier.normalize(word)) != null

    /** Unit-length meaning of [text], or null if none of its words are known. */
    fun embed(text: String): FloatArray? {
        val acc = FloatArray(dims)
        var found = false
        for (t in TopicClassifier.normalize(text).split(" ")) {
            if (t.isEmpty()) continue
            val i = lookup(t) ?: continue
            val w = weights[i] * scales[i]
            val off = i * dims
            for (d in 0 until dims) acc[d] += w * data[off + d]
            found = true
        }
        if (!found) return null
        val n = sqrt(acc.sumOf { (it * it).toDouble() }).toFloat()
        if (n == 0f) return null
        for (d in 0 until dims) acc[d] /= n
        return acc
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun load(words: InputStream, vectors: InputStream): Meaning {
            val list = words.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }
            val buf = ByteBuffer.wrap(vectors.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "UTHV") { "not a meaning table" }
            require(buf.int == 1) { "unknown version" }
            val count = buf.int
            val dims = buf.int
            require(count == list.size) { "words and vectors don't match" }
            val scales = FloatArray(count) { buf.float }
            val weights = FloatArray(count) { (buf.get().toInt() and 0xFF) / 255f }
            val data = ByteArray(count * dims).also { buf.get(it) }
            val index = HashMap<String, Int>(count * 2)
            list.forEachIndexed { i, w -> index.putIfAbsent(w, i) }
            return Meaning(index, dims, scales, weights, data)
        }
    }
}
