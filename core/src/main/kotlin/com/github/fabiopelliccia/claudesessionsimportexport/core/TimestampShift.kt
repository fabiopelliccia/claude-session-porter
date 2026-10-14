package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shifts the timestamps of a transcript by a fixed [Duration]. Used on import so a session lands
 * around "now" instead of when it was originally recorded, while the spacing between its own
 * messages stays exactly the same - every timestamp of a session moves by the same delta.
 *
 * Values are recognized **by shape** and written back in the shape they were read in: an ISO-8601
 * UTC instant keeps its number of fraction digits (`...:00Z`, `...:00.000Z`, ...), an epoch in
 * milliseconds stays a 13 digit number. Anything that matches neither shape is returned as `null`,
 * that is left untouched: a timestamp that is not shifted is better than a transcript line that is
 * corrupted.
 */
object TimestampShift {

    private val ISO_UTC = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$""")

    private val EPOCH_MILLIS_RANGE = 1_000_000_000_000L..9_999_999_999_999L

    // Locale.ROOT keeps digits plain ASCII regardless of the JVM's default locale.
    private val SECONDS_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** The delta that moves [anchor] to "now" - `Duration.ZERO` when [anchor] can't be parsed. */
    fun deltaToNow(anchor: String?, now: Instant = Instant.now()): Duration {
        val instant = anchor?.let { parse(it) } ?: return Duration.ZERO
        return Duration.between(instant, now)
    }

    fun parse(value: String): Instant? =
        if (ISO_UTC.matches(value)) runCatching { Instant.parse(value) }.getOrNull() else null

    /** Returns the shifted value, or `null` when [value] isn't a supported instant or [delta] is zero. */
    fun shift(value: String, delta: Duration): String? {
        if (delta.isZero) return null
        val match = ISO_UTC.matchEntire(value) ?: return null
        val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return null
        val shifted = instant.plus(delta)
        val fractionDigits = match.groupValues[2].length
        val fraction = if (fractionDigits == 0) {
            ""
        } else {
            // The nano-of-second padded to 9 digits, cut back to as many digits as the input had.
            "." + "%09d".format(Locale.ROOT, shifted.nano).take(fractionDigits)
        }
        return SECONDS_FORMATTER.format(shifted) + fraction + "Z"
    }

    /** Returns the shifted epoch, or `null` when [value] isn't a 13 digit epoch in milliseconds. */
    fun shiftEpochMillis(value: Long, delta: Duration): Long? {
        if (delta.isZero || value !in EPOCH_MILLIS_RANGE) return null
        return value + delta.toMillis()
    }
}
