package com.riziuzi.utho.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sound-alike matching between English words and the same words written in Devanagari.
 * Android's Hindi recogniser writes English words in Hindi script ("maths" -> "मैथ्स",
 * "fundamental rights" -> "फंडामेंटल राइट्स"), so both sides are reduced to a consonant skeleton:
 * मैथ्स -> maithsa -> "mts"  and  maths -> "mts".
 */
object Phonetic {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ii", 'उ' to "u", 'ऊ' to "uu", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ii", 'ु' to "u", 'ू' to "uu", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
    private const val VIRAMA = '्'

    fun hasDevanagari(s: String) = s.any { it in 'ऀ'..'ॿ' }

    /** Rough Devanagari -> Latin transliteration (only used for matching). */
    fun toLatin(input: String): String {
        val s = input.replace("़", "")
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cons = consonants[c]
            if (cons != null) {
                sb.append(cons)
                val next = s.getOrNull(i + 1)
                when {
                    next == VIRAMA -> i += 2
                    next != null && matras.containsKey(next) -> {
                        sb.append(matras.getValue(next))
                        i += 2
                    }
                    else -> {
                        sb.append('a')
                        i++
                    }
                }
                continue
            }
            when {
                vowels.containsKey(c) -> sb.append(vowels.getValue(c))
                matras.containsKey(c) -> sb.append(matras.getValue(c))
                c == 'ं' || c == 'ँ' -> sb.append('n')
                c == 'ः' -> sb.append('h')
                c == VIRAMA -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Consonant skeleton of a Latin word, after mapping English spellings to sounds. */
    fun skeleton(latin: String): String {
        var s = latin.lowercase().filter { it.isLetter() && it.code < 128 }
        s = s.replace("tion", "shan").replace("sion", "shan").replace("ph", "f").replace("ck", "k")
            .replace("x", "ks").replace("q", "k").replace("dg", "j").replace("wh", "v")
        s = s.replace("chh", "C").replace("ch", "C")
        s = s.replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        s = s.replace(Regex("g(?=[eiy])"), "j")
        s = s.replace("sh", "s").replace("th", "t").replace("dh", "d").replace("bh", "b")
            .replace("gh", "g").replace("kh", "k").replace("jh", "j").replace("rh", "r")
        s = s.replace('C', 'c').replace('w', 'v').replace('z', 'j')
        val out = StringBuilder()
        for (ch in s) {
            if (ch in "aeiouyh") continue
            if (out.isNotEmpty() && out.last() == ch) continue
            out.append(ch)
        }
        return out.toString()
    }

    /** Skeleton of one word in either script. */
    fun key(word: String): String = skeleton(if (hasDevanagari(word)) toLatin(word) else word)

    fun keys(phrase: String): List<String> = phrase.split(" ").filter { it.isNotBlank() }.map { key(it) }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** Same skeleton, or one sound off for longer words, or a longer form (plural) of it. */
    fun close(textKey: String, termKey: String): Boolean {
        if (textKey.isEmpty() || termKey.isEmpty()) return false
        if (textKey == termKey) return true
        if (min(textKey.length, termKey.length) >= 5 && editDistanceAtMostOne(textKey, termKey)) return true
        return termKey.length >= 5 && textKey.startsWith(termKey)
    }

    /** Does the phrase (as skeletons) appear among the text's word skeletons? */
    fun contains(textKeys: List<String>, termKeys: List<String>, minLength: Int): Boolean {
        if (termKeys.isEmpty() || termKeys.any { it.isEmpty() }) return false
        if (termKeys.sumOf { it.length } < minLength) return false
        if (termKeys.size == 1) return textKeys.any { close(it, termKeys[0]) }
        for (start in 0..textKeys.size - termKeys.size) {
            if (termKeys.indices.all { k -> close(textKeys[start + k], termKeys[k]) }) return true
        }
        return false
    }
}
