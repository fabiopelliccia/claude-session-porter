package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.MessageBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

/** The same checks the core runs on its own bundle, on the one of the CLI. */
class CliBundleTest {

    private val translations = listOf("en", "it", "fr", "de", "es", "pt", "ja", "zh", "ko")

    private val original: Locale = Locale.getDefault(Locale.Category.DISPLAY)

    @After
    fun restore() {
        Locale.setDefault(Locale.Category.DISPLAY, original)
        MessageBundle.displayLanguage = { null }
    }

    @Test
    fun `follows the language of the machine and falls back to english`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("it-IT"))
        assertEquals("Nessuna sessione trovata.", CliBundle.message("list.empty"))
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("ja-JP"))
        assertEquals("セッションが見つかりません。", CliBundle.message("list.empty"))
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("fi-FI"))
        assertEquals("No sessions found.", CliBundle.message("list.empty"))
    }

    @Test
    fun `every translation carries exactly the keys of the English bundle`() {
        val base = load("")
        for (language in translations) {
            val translated = load("_$language")
            assertEquals("missing in $language", emptySet<String>(), base.keys - translated.keys)
            assertEquals("unknown in $language", emptySet<String>(), translated.keys - base.keys)
        }
    }

    @Test
    fun `every translation keeps the placeholders of the English text`() {
        val base = load("")
        val placeholder = Regex("\\{(\\d)}")
        for (language in translations) {
            for ((key, value) in load("_$language")) {
                val expected = placeholder.findAll(base.getValue(key)).map { it.value }.toSet()
                val actual = placeholder.findAll(value).map { it.value }.toSet()
                assertEquals("$language $key", expected, actual)
            }
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

    @Test
    fun `every key the code asks for is in the bundle`() {
        // The tests run from the folder of the module: the sources are next to them.
        val sources = Paths.get("src", "main", "kotlin")
        val keys = Files.walk(sources).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.toList().flatMap { file ->
                Regex("\"((?:error|warning|label|hint|table|list|inspect|export|import|version|doctor)\\.[A-Za-z0-9.]+)\"")
                    .findAll(Files.readString(file)).map { it.groupValues[1] }.toList()
            }
        }.toSet()
        assertTrue("no key found: is the working directory the module?", keys.size > 40)
        val bundle = load("")
        // A few keys are the core's own (the CLI shows the core's message for an unsafe archive).
        val coreKeys = load("", "SessionPorterCoreBundle").keys
        assertEquals(emptySet<String>(), keys - bundle.keys - coreKeys)
        // Asked for by a computed name: doctor.check.<id>.
        for (id in listOf("java", "home", "projects", "logs")) assertTrue(bundle.containsKey("doctor.check.$id"))
    }

    private fun load(suffix: String, baseName: String = "SessionPorterCliBundle"): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("messages/$baseName$suffix.properties")
            ?: error("messages/$baseName$suffix.properties not found")
        val properties = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { properties.load(it) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }
}
