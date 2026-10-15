package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

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
