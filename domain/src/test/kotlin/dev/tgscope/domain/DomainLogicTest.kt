package dev.tgscope.domain

import dev.tgscope.core.database.DayCount
import dev.tgscope.domain.export.Anonymizer
import dev.tgscope.domain.export.Exporter
import dev.tgscope.domain.export.Exporter.Companion.serverId
import dev.tgscope.core.database.PostEntity
import dev.tgscope.domain.analytics.ContentAnalytics
import dev.tgscope.domain.analytics.TextAnalytics
import dev.tgscope.domain.llm.LlmAnalyzer
import dev.tgscope.domain.llm.LlmClient
import dev.tgscope.domain.llm.LlmProviders
import dev.tgscope.domain.llm.Prompts
import org.junit.Assert.assertTrue
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

    @Test
    fun textAnalyticsExtractsEntities() {
        val text = "Новый #релиз и #Android! Пишите @tgscope_bot, подробности на https://www.example.com/x и t.me/channel"
        assertEquals(listOf("#релиз", "#android"), TextAnalytics.hashtags(text))
        assertEquals(listOf("@tgscope_bot"), TextAnalytics.mentions(text))
        assertTrue("example.com" in TextAnalytics.domains(text))
        assertTrue("t.me" in TextAnalytics.domains(text))
        assertTrue(TextAnalytics.words("и в на это очень Telegram каналы").containsAll(listOf("telegram", "каналы")))
        assertTrue("это" !in TextAnalytics.words("это"))
    }

    @Test
    fun seoScoreRewardsGoodPosts() {
        fun post(text: String) = PostEntity(
            chatId = 1, messageId = 1, date = 0, editDate = 0, text = text, views = 100, forwards = 0,
            replyCount = 0, mediaType = null, mediaFileId = null, link = "", updatedAt = 0,
        )
        val good = post("Как выбрать Android смартфон в 2026\n\nРазбираем главное. Подробнее: https://example.com #android #гайд\nА вы что думаете?")
        val bad = post("ок")
        val goodScore = ContentAnalytics.seoScore(good, setOf("android")).score
        val badScore = ContentAnalytics.seoScore(bad, setOf("android"))
        assertTrue("good=$goodScore", goodScore >= 80)
        assertTrue(badScore.score < 40)
        assertTrue(badScore.tips.isNotEmpty())
    }

    @Test
    fun llmBaseUrlStripsKnownSuffixes() {
        assertEquals("https://api.anthropic.com/v1", LlmClient.base("https://api.anthropic.com/v1/messages"))
        assertEquals("https://x/v1", LlmClient.base(" https://x/v1/ "))
    }

    @Test
    fun providerCatalogIsConsistent() {
        assertEquals(LlmProviders.all.size, LlmProviders.all.map { it.id }.toSet().size)
        LlmProviders.all.filter { it.needsKey }.forEach { assertTrue(it.id, it.keyUrl?.startsWith("https://") == true) }
        assertEquals("custom", LlmProviders.byId("nope").id)
    }

    @Test
    fun promptCatalogHasTasksInBothLanguages() {
        assertEquals(Prompts.kinds.size, Prompts.kinds.map { it.id }.toSet().size)
        Prompts.kinds.forEach {
            assertTrue(it.id, it.taskRu.isNotBlank() && it.taskEn.isNotBlank())
            assertTrue(it.id, it.category in Prompts.categories)
        }
        assertTrue(Prompts.system(Prompts.CUSTOM, "ru", "найди цены").endsWith("найди цены"))
    }
}
