package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException

/**
 * A file above this size is never read whole into memory: the export copies it as it is, after scanning it
 * in chunks, and the import copies it as it is. Text up to this size is anonymized (and rendered back on
 * import) in memory, which is where the privacy of the archive is decided: a larger text file is reported
 * like a binary one and counts for the strict privacy check.
 */
internal const val LARGE_FILE_BYTES = 32L * 1024 * 1024

/** How a text file is stored: plain or BOM-marked UTF-8, or UTF-16 with its byte order mark (PowerShell's `>`). */
internal enum class TextEncoding(val byteOrderMark: ByteArray, val charset: Charset) {
    UTF_8(byteArrayOf(), Charsets.UTF_8),
    UTF_16_LE(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), Charsets.UTF_16LE),
    UTF_16_BE(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), Charsets.UTF_16BE),
}

/** The [text] of a file and the [encoding] it has to be written back in. */
internal class DecodedText(val text: String, val encoding: TextEncoding) {

    /** [newText] as the file stores it: with the same byte order mark and in the same encoding as it was read. */
    fun encode(newText: String): ByteArray = encoding.byteOrderMark + newText.toByteArray(encoding.charset)
}

/**
 * [bytes] as text, or `null` when they are not text: invalid UTF-8, or UTF-16 with an odd number of bytes or
 * with a lone surrogate. A NUL does not make a file binary (`find -print0` writes valid UTF-8 with NULs): any
 * file that decodes strictly is text, and a text file keeps what it had - the BOM of UTF-8 stays in the
 * text, the one of UTF-16 is dropped here and written back by [DecodedText.encode].
 */
internal fun decodeTextFile(bytes: ByteArray): DecodedText? {
    val encoding = encodingOf(bytes)
    val bomLength = encoding.byteOrderMark.size
    val text = decodeStrictly(bytes, bomLength, bytes.size, encoding.charset) ?: return null
    return DecodedText(text, encoding)
}

private fun encodingOf(bytes: ByteArray): TextEncoding =
    TextEncoding.entries.firstOrNull { encoding ->
        encoding.byteOrderMark.isNotEmpty() && bytes.size >= encoding.byteOrderMark.size &&
            encoding.byteOrderMark.indices.all { bytes[it] == encoding.byteOrderMark[it] }
    } ?: TextEncoding.UTF_8

/** All of [input] when it is [limit] bytes or fewer, `null` when it is longer (then nothing more than `limit + 1` bytes were read). */
internal fun readAtMost(input: java.io.InputStream, limit: Long): ByteArray? {
    val bytes = input.readNBytes((limit + 1).toInt())
    return if (bytes.size > limit) null else bytes
}

/**
 * The [text] of a transcript up to its last complete line, and whether a fragment after it was [partialLineDropped].
 * A transcript of a live session may end in the middle of a line, and even in the middle of a character.
 */
internal class CompleteLines(val text: String, val partialLineDropped: Boolean)

private const val NEWLINE: Byte = 0x0A

/**
 * The complete lines of a transcript, found on the bytes (a cut inside a multi-byte character must not make the
 * whole file unreadable) and then decoded strictly. What follows the last newline is a line still being written
 * and is left out, unless it is a whole JSON document on its own (a writer that did not finish with a newline):
 * then it is kept, with the newline it lacks, so that a later append does not glue to it. Blanks after the last
 * newline are not a fragment. Throws [java.nio.charset.MalformedInputException] when the complete lines are not UTF-8.
 */
internal fun completeLinesOf(bytes: ByteArray): CompleteLines {
    val tailStart = bytes.lastIndexOf(NEWLINE) + 1
    if (isBlank(bytes, tailStart)) return CompleteLines(decodeLines(bytes, bytes.size), partialLineDropped = false)

    val tail = decodeStrictly(bytes, tailStart, bytes.size)
    if (tail != null && JsonLines.parse(tail) != null) {
        return CompleteLines(decodeLines(bytes, bytes.size) + "\n", partialLineDropped = false)
    }
    return CompleteLines(decodeLines(bytes, tailStart), partialLineDropped = true)
}

private fun decodeLines(bytes: ByteArray, to: Int): String =
    decodeStrictly(bytes, 0, to) ?: throw MalformedInputException(0)

/** Whether `bytes[from..]` is nothing but spaces, tabs and carriage returns. */
private fun isBlank(bytes: ByteArray, from: Int): Boolean {
    for (index in from until bytes.size) {
        val byte = bytes[index].toInt()
        if (byte != ' '.code && byte != '\t'.code && byte != '\r'.code) return false
    }
    return true
}

/** `bytes[from until to]` decoded in [charset], or `null` when it is not valid in it. */
internal fun decodeStrictly(bytes: ByteArray, from: Int, to: Int, charset: Charset = Charsets.UTF_8): String? =
    try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, to - from)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
