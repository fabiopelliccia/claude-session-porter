package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.util.regex.Pattern

/**
 * The regex fragments a folder or user name is searched with. Every fragment is meant to be used
 * case-insensitively (see [AnchorMatcher]).
 */
internal object PathNamePatterns {

    /** A folder name as it reads in a plain path. */
    fun plain(name: String): String = Pattern.quote(name)

    /** A folder name as it reads in a URL: every byte outside the unreserved set is `%XX`. */
    fun url(name: String): String {
        val out = StringBuilder()
        var run = StringBuilder()
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint < 128 && (codePoint.toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" })) {
                run.append(codePoint.toChar())
            } else {
                if (run.isNotEmpty()) out.append(Pattern.quote(run.toString()))
                run = StringBuilder()
                for (byte in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
                    out.append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }
        if (run.isNotEmpty()) out.append(Pattern.quote(run.toString()))
        return out.toString()
    }
}
