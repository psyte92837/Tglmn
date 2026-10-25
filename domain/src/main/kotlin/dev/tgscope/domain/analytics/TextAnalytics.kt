package dev.tgscope.domain.analytics

data class Term(val text: String, val count: Int)

/** Tokenization, keywords, n-grams, hashtags, mentions and link domains for ru/en texts. */
object TextAnalytics {
    private val wordRegex = Regex("[\\p{L}][\\p{L}\\p{N}_'-]+")
    private val hashtagRegex = Regex("(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]{2,}")
    private val mentionRegex = Regex("(?<![\\p{L}\\p{N}_])@[A-Za-z][A-Za-z0-9_]{3,31}")
    private val urlRegex = Regex("(?i)\\b(?:https?://)?((?:[a-z0-9-]+\\.)+[a-z]{2,})(?:/[^\\s]*)?")
    private val linkRegex = Regex("(?i)https?://[^\\s]+|\\bt\\.me/[^\\s]+")

    val stopwords: Set<String> = (
        // Russian
        "и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне было вот от меня " +
            "еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до вас нибудь опять уж вам ведь " +
            "там потом себя ничего ей может они тут где есть надо ней для мы тебя их чем была сам чтоб без будто чего раз " +
            "тоже себе под будет ж тогда кто этот того потому этого какой совсем ним здесь этом один почти мой тем чтобы " +
            "нее сейчас были куда зачем всех никогда можно при наконец два об другой хоть после над больше тот через эти " +
            "нас про всего них какая много разве три эту моя впрочем хорошо свою этой перед иногда лучше чуть том нельзя " +
            "такой им более всегда конечно всю между это эта эти весь всё вообще очень просто тоже который которые которая " +
            "которых также свой своих своей своего так типа пока будут есть нет какие какой каждый ещё вот вон либо " +
            // English
            "the a an and or but if then else of to in on at by for with from as is are was were be been being it its this " +
            "that these those i you he she we they me him her us them my your his our their not no yes do does did have has " +
            "had will would can could should may might must just so than too very also about into over after before up down " +
            "out more most some any all each other such only own same what which who whom when where why how there here " +
            "http https www com ru org net"
        ).split(' ').filter { it.isNotBlank() }.toSet()

    fun words(text: String): List<String> = wordRegex.findAll(text.lowercase())
        .map { it.value.trim('\'', '-') }
        .filter { it.length >= 3 && it !in stopwords && !it.all(Char::isDigit) }
        .toList()

    fun topWords(texts: Sequence<String>, limit: Int = 30): List<Term> {
        val counts = HashMap<String, Int>()
        texts.forEach { t -> words(stripLinks(t)).forEach { counts.merge(it, 1, Int::plus) } }
        return counts.top(limit)
    }

    fun topBigrams(texts: Sequence<String>, limit: Int = 20): List<Term> {
        val counts = HashMap<String, Int>()
        texts.forEach { t ->
            val w = words(stripLinks(t))
            for (i in 0 until w.size - 1) counts.merge("${w[i]} ${w[i + 1]}", 1, Int::plus)
        }
        return counts.filterValues { it >= 2 }.top(limit)
    }

    fun hashtags(text: String): List<String> = hashtagRegex.findAll(text).map { it.value.lowercase() }.distinct().toList()

    fun mentions(text: String): List<String> = mentionRegex.findAll(text).map { it.value.lowercase() }.distinct().toList()

    fun domains(text: String): List<String> = linkRegex.findAll(text).mapNotNull { m ->
        urlRegex.find(m.value)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")
    }.distinct().toList()

    fun hasLink(text: String): Boolean = linkRegex.containsMatchIn(text)

    fun countTerms(items: Sequence<String>, limit: Int = 20): List<Term> {
        val counts = HashMap<String, Int>()
        items.forEach { counts.merge(it, 1, Int::plus) }
        return counts.top(limit)
    }

    private fun stripLinks(text: String) = linkRegex.replace(text, " ")

    private fun Map<String, Int>.top(limit: Int): List<Term> =
        entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Term(it.key, it.value) }
}
