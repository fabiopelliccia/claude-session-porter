package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

class ClaudeSessionsBundleTest {

    private val translations = listOf("en", "it", "fr", "de", "es", "pt", "ja", "zh", "ko")

    private val original: Locale = Locale.getDefault(Locale.Category.DISPLAY)

    @After
    fun restore() {
        Locale.setDefault(Locale.Category.DISPLAY, original)
        MessageBundle.displayLanguage = { null }
    }

    private fun skipLabel() = ClaudeSessionsBundle.message("conflict.skip")

    @Test
    fun `follows the regional settings of the machine`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("it-IT"))
        assertEquals("Salta le sessioni già presenti", skipLabel())

        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("de-AT"))
        assertEquals("Bereits vorhandene Sitzungen überspringen", skipLabel())
    }

    @Test
    fun `an explicit IDE language wins over the regional settings, English does not`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("it-IT"))
        MessageBundle.displayLanguage = { Locale.FRENCH }
        assertEquals("Ignorer les sessions déjà présentes", skipLabel())

        // An English IDE is simply the default one, not a choice: the machine language still wins.
        MessageBundle.displayLanguage = { Locale.ENGLISH }
        assertEquals("Salta le sessioni già presenti", skipLabel())
    }

    @Test
    fun `keeps the non latin translations readable`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("ja-JP"))
        assertEquals("既存のセッションはスキップする", skipLabel())
    }

    @Test
    fun `falls back to english for an untranslated language`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("fi-FI"))
        assertEquals("Skip sessions that already exist", skipLabel())

        Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
        assertEquals("Skip sessions that already exist", skipLabel())
    }

    @Test
    fun `placeholders are substituted and apostrophes survive MessageFormat`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("it-IT"))
        assertEquals(
            "Il formato dell'archivio 3 è più recente della versione supportata 1. Aggiorna il plugin.",
            ClaudeSessionsBundle.message("error.archiveTooNew", 3, 1),
        )
    }

    @Test
    fun `every translation carries exactly the keys of the English bundle`() {
        val base = load("")
        for (language in translations) {
            val translated = load("_$language")
            assertEquals("missing in $language", emptySet<String>(), base.keys - translated.keys)
            assertEquals("unknown in $language", emptySet<String>(), translated.keys - base.keys)
        }
        for (number in 1..ImportDiagnostics.CHECK_COUNT) {
            assertTrue("diagnosis.check.$number", base.containsKey("diagnosis.check.$number"))
        }
    }

    @Test
    fun `apostrophes are doubled exactly where MessageFormat needs it`() {
        val base = load("")
        val single = Regex("(?<!')'(?!')")
        for (suffix in listOf("") + translations.map { "_$it" }) {
            for ((key, value) in load(suffix)) {
                val placeholders = Regex("\\{(\\d)}").findAll(base.getValue(key)).count()
                if (placeholders > 0) {
                    assertTrue("$suffix $key has a single apostrophe", !single.containsMatchIn(value))
                    val arguments = Array<Any>(placeholders) { "ARG$it" }
                    val formatted = MessageFormat(value).format(arguments)
                    for (i in 0 until placeholders) assertTrue("$suffix $key lost {$i}", formatted.contains("ARG$i"))
                } else {
                    assertTrue("$suffix $key doubles an apostrophe it prints twice", !value.contains("''"))
                }
            }
        }
    }

    private fun load(suffix: String): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("messages/SessionPorterCoreBundle$suffix.properties")
            ?: error("messages/SessionPorterCoreBundle$suffix.properties not found")
        val properties = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { properties.load(it) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }
}
