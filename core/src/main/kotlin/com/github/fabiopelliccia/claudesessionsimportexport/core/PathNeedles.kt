package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.text.Normalizer

/**
 * The needles of the cheap pre-filter of [PathAnonymizer]: short texts of which at least one is in every
 * line (or file) that holds an anchor, whatever the spelling. A line without any of them is skipped
 * without being parsed or matched, which is what keeps a transcript of many megabytes fast. They are
 * compared whatever the case (`JsonLines.containsIgnoreCase`), so they are kept as they are.
 *
 * What every spelling of a name has in common is its run of ASCII letters and digits: composed or
 * decomposed, escaped as a JSON unicode escape (a backslash, `u` and four hex digits), percent-encoded, encoded as a project folder name (where only the
 * letters and digits of ASCII survive) - those characters are never changed. So the longest such run of
 * the name is the needle. A name without a useful run (`Алиса`, `Zoë`) is found by one of its
 * non-ASCII characters in each of its spellings instead, and its folder name needs a needle of its own.
 */
internal object PathNeedles {

    /** A shorter run would let nearly every line through. */
    private const val USEFUL_RUN_LENGTH = 3

    private val ASCII_RUN = Regex("[A-Za-z0-9]+")

    fun of(
        anchors: List<AnchorPath>,
        userNames: List<String>,
        shortStems: List<String>,
        shortHomeLeaves: List<String>,
    ): List<String> {
        val needles = mutableListOf<String>()

        // An anchor below another one is spelled with the other's folder names too (every form of
        // a project root under the home names the home), so its own leaf, often a one-letter
        // `src` or `X`, would only let every line through the filter.
        val outermost = anchors.filter { anchor -> anchors.none { other -> other !== anchor && anchor.startsWith(other) } }
        for (anchor in outermost) {
            val forLeaf = forName(anchor.leaf)
            needles += forLeaf.needles
            // The folder name keeps no non-ASCII character: the whole encoded path is the only thing to look for.
            if (forLeaf.byCharacter) anchor.encodedFolderName?.let { needles += it }
        }
        for (name in userNames) needles += forName(name).needles

        needles += shortStems
        // `ZO5F2~1` is `ZO5F2-1` as a project folder name: only what comes before the tilde is in every spelling.
        needles += shortHomeLeaves.map { it.substringBefore('~') }

        return withoutRedundantNeedles(needles.filter { it.isNotEmpty() })
    }

    /** The needles of one name, and whether they are spellings of a character instead of a run of ASCII. */
    private class NameNeedles(val needles: List<String>, val byCharacter: Boolean)

    private fun forName(givenName: String): NameNeedles {
        // Composed, so that a letter with an accent is not an ASCII letter followed by a combining mark:
        // a run of ASCII letters that is in the composed name is in the decomposed one too.
        val name = Normalizer.normalize(givenName, Normalizer.Form.NFC)
        val longestRun = ASCII_RUN.findAll(name).map { it.value }.maxByOrNull { it.length }
        if (longestRun != null && longestRun.length >= USEFUL_RUN_LENGTH) return NameNeedles(listOf(longestRun), byCharacter = false)

        val firstNonAscii = name.codePoints().filter { it >= 128 }.findFirst()
        if (firstNonAscii.isPresent) return NameNeedles(spellingsOf(firstNonAscii.asInt), byCharacter = true)

        // A short plain name (`X`, `Li`): the run is all there is.
        return NameNeedles(listOf(longestRun ?: name), byCharacter = false)
    }

    /** One non-ASCII character as it appears in each spelling: itself, decomposed, the JSON escape without its backslash (`u00eb`) and `%C3%AB`. */
    private fun spellingsOf(codePoint: Int): List<String> {
        val text = String(Character.toChars(codePoint))
        val spellings = mutableListOf(text)
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        if (decomposed != text) spellings += decomposed
        // The first escape is enough for a character written as two surrogates.
        spellings += "u%04x".format(text[0].code)
        spellings += text.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }
        return spellings
    }

    /** A needle that holds another one adds nothing: any line it is in, the other is in too. */
    private fun withoutRedundantNeedles(needles: List<String>): List<String> {
        val kept = mutableListOf<String>()
        for (needle in needles.distinctBy { it.lowercase() }.sortedBy { it.length }) {
            if (kept.none { shorter -> needle.contains(shorter, ignoreCase = true) }) kept += needle
        }
        return kept
    }
}
