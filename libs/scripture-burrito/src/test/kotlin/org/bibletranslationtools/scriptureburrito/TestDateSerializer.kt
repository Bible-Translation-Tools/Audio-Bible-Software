package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.Date

/**
 * The `dateCreated` shapes real burritos contain.
 *
 * A single exported wrapper carries two of them at once — the wrapper's own metadata uses a
 * microsecond instant, the audio and text burritos nested inside it use a bare date — so a reader
 * that handles only one shape cannot import that file. Both cases below are taken verbatim from
 * `burrito-wrapper_en-ulb-tit-2026-06-05.zip`.
 */
class TestDateSerializer {

    private fun read(literal: String): Date =
        Json.decodeFromString(DateSerializer, "\"$literal\"")

    private fun instant(literal: String): Instant = read(literal).toInstant()

    @Test
    fun `reads a bare date as midnight UTC`() {
        // Threw "Unparseable date" before, which surfaced as the misleading
        // "Could not find both audio and text burritos in wrapper".
        assertEquals(Instant.parse("2026-06-05T00:00:00Z"), instant("2026-06-05"))
    }

    @Test
    fun `reads fractional seconds beyond milliseconds without shifting the time`() {
        // The regression this pins is silent, not loud: a lenient SimpleDateFormat with a `.SSS`
        // field read all six digits of `620908` as milliseconds and moved the timestamp ten
        // minutes into the future — 13:38:34.908 — rather than failing.
        //
        // The sub-millisecond digits are dropped, because java.util.Date cannot hold them; that
        // truncation is the schema's choice of type and is what Jackson did too. Losing 908
        // MICROseconds is not the bug. Gaining ten minutes was.
        assertEquals(
            Instant.parse("2026-06-05T13:28:14.620Z"),
            instant("2026-06-05T13:28:14.620908Z")
        )
    }

    @Test
    fun `reads the other ISO-8601 shapes the spec allows`() {
        assertEquals(Instant.parse("2026-06-05T13:28:14Z"), instant("2026-06-05T13:28:14Z"))
        assertEquals(Instant.parse("2026-06-05T13:28:14.620Z"), instant("2026-06-05T13:28:14.620Z"))
        // An explicit offset is normalized to the same instant.
        assertEquals(Instant.parse("2026-06-05T07:58:14Z"), instant("2026-06-05T13:28:14+05:30"))
        // No zone at all: read as UTC, so the same file parses the same way on every machine.
        assertEquals(Instant.parse("2026-06-05T13:28:14Z"), instant("2026-06-05T13:28:14"))
    }

    @Test
    fun `writes a millisecond UTC instant`() {
        val json = Json.encodeToString(DateSerializer, Date.from(Instant.parse("2026-06-05T13:28:14.620Z")))
        assertEquals("\"2026-06-05T13:28:14.620Z\"", json)
    }

    @Test
    fun `round trips through the written format`() {
        val original = Date.from(Instant.parse("2026-06-05T13:28:14.620Z"))
        val json = Json.encodeToString(DateSerializer, original)
        assertEquals(original, Json.decodeFromString(DateSerializer, json))
    }

    @Test
    fun `rejects a value that is not a date at all`() {
        // Still a failure, just not a silent one — and not a ParseException escaping a serializer.
        assertThrows(SerializationException::class.java) { read("not-a-date") }
    }
}
