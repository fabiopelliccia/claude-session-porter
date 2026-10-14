package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TimestampShiftTest {

    private val oneHour = Duration.ofHours(1)

    @Test
    fun `an ISO instant is written back in the shape it was read in`() {
        assertEquals("2026-01-01T11:00:00.123Z", TimestampShift.shift("2026-01-01T10:00:00.123Z", oneHour))
        assertEquals("2026-01-01T11:00:00Z", TimestampShift.shift("2026-01-01T10:00:00Z", oneHour))
        assertEquals("2026-01-01T11:00:00.123456Z", TimestampShift.shift("2026-01-01T10:00:00.123456Z", oneHour))
        // A zero fraction keeps its digits instead of being dropped, as Instant.toString() would do.
        assertEquals("2026-01-01T11:00:00.000Z", TimestampShift.shift("2026-01-01T10:00:00.000Z", oneHour))
    }

    @Test
    fun `crossing midnight and a sub-second delta keep the shape too`() {
        assertEquals(
            "2026-01-02T00:00:00.500Z",
            TimestampShift.shift("2026-01-01T23:59:59.750Z", Duration.ofMillis(750)),
        )
    }

    @Test
    fun `a value of any other shape is left untouched`() {
        assertNull(TimestampShift.shift("2026-01-01T10:00:00+02:00", oneHour))
        assertNull(TimestampShift.shift("2026-01-01 10:00:00", oneHour))
        assertNull(TimestampShift.shift("yesterday", oneHour))
        assertNull(TimestampShift.shift("2026-01-01T10:00:00.000Z", Duration.ZERO))
    }

    @Test
    fun `an epoch in milliseconds is shifted only when it has 13 digits`() {
        assertEquals(1_767_265_200_000L, TimestampShift.shiftEpochMillis(1_767_261_600_000L, oneHour))
        assertNull(TimestampShift.shiftEpochMillis(1_767_261_600L, oneHour))
        assertNull(TimestampShift.shiftEpochMillis(42L, oneHour))
        assertNull(TimestampShift.shiftEpochMillis(1_767_261_600_000L, Duration.ZERO))
    }

    @Test
    fun `the delta moves the anchor exactly to now`() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        assertEquals(Duration.ofDays(1), TimestampShift.deltaToNow("2026-09-22T12:00:00.000Z", now))
        assertEquals(Duration.ZERO, TimestampShift.deltaToNow("not a timestamp", now))
        assertEquals(Duration.ZERO, TimestampShift.deltaToNow(null, now))
    }
}
