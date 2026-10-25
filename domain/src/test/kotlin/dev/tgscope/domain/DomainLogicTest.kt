package dev.tgscope.domain

import dev.tgscope.core.database.DayCount
import dev.tgscope.domain.export.Anonymizer
import dev.tgscope.domain.export.Exporter
import dev.tgscope.domain.export.Exporter.Companion.serverId
import dev.tgscope.domain.llm.LlmAnalyzer
import dev.tgscope.domain.stats.StatsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainLogicTest {

    @Test
    fun ftsQueryEscapesAndPrefixes() {
        assertEquals("\"hello\"* \"world\"*", StatsRepository.ftsQuery("  hello  wor\"ld* "))
        assertNull(StatsRepository.ftsQuery("   "))
    }

    @Test
    fun fillDaysProducesContinuousAxis() {
        val from = 0L // 1970-01-01
        val to = 3 * 86_400L
        val filled = StatsRepository.fillDays(listOf(DayCount("1970-01-02", 5)), from, to)
        assertEquals(listOf("1970-01-01", "1970-01-02", "1970-01-03"), filled.map { it.day })
        assertEquals(listOf(0, 5, 0), filled.map { it.count })
    }

    @Test
    fun csvEscaping() {
        assertEquals("plain", Exporter.csvEscape("plain"))
        assertEquals("\"a,b\"", Exporter.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Exporter.csvEscape("say \"hi\""))
        assertEquals("\"line\nbreak\"", Exporter.csvEscape("line\nbreak"))
    }

    @Test
    fun dateFormatMatchesOriginalTool() {
        assertEquals("1970-01-01 00:00:00+00:00", Exporter.formatDate(0))
    }

    @Test
    fun serverIdShiftsTdlibIds() {
        assertEquals(101L, (101L shl 20).serverId())
    }

    @Test
    fun anonymizerIsStableAndSaltDependent() {
        val a = Anonymizer("salt")
        assertEquals(a.username(42), Anonymizer("salt").username(42))
        assertEquals(a.userId(42), Anonymizer("salt").userId(42))
        assertNotEquals(a.username(42), Anonymizer("other").username(42))
        assertNotEquals(a.username(42), a.username(43))
    }

    @Test
    fun endpointUrlNormalization() {
        assertEquals("https://api.openai.com/v1/chat/completions", LlmAnalyzer.endpointUrl("https://api.openai.com/v1/"))
        assertEquals("http://h:1/v1/chat/completions", LlmAnalyzer.endpointUrl("http://h:1/v1/chat/completions"))
    }
}
