package dev.tgscope.domain.llm

enum class AnalysisScope { POST, CHANNEL, BOTH }

data class AnalysisKind(
    val id: String,
    val category: String,
    val titleRu: String,
    val titleEn: String,
    val taskRu: String,
    val taskEn: String,
    val scope: AnalysisScope = AnalysisScope.BOTH,
)

/**
 * Catalog of built-in analyses. Tasks are written in Russian and English; Chinese output uses the
 * English task with a Chinese answer instruction.
 */
object Prompts {
    val languages = listOf("ru", "en", "zh")
    const val CUSTOM = "custom"

    val categories = listOf("Сводки", "Аудитория", "Модерация", "Контент и SEO")

    val kinds: List<AnalysisKind> = listOf(
        // --- Сводки ---
        AnalysisKind(
            "summary", "Сводки", "Сводка", "Summary",
            "Сделай краткую сводку обсуждения: о чём пост, как отреагировала аудитория, ключевые мнения и вопросы, общий вывод.",
            "Write a concise summary: what the post is about, how the audience reacted, key opinions and questions, overall takeaway.",
        ),
        AnalysisKind(
            "tldr", "Сводки", "TL;DR в 3 пунктах", "TL;DR in 3 bullets",
            "Дай ровно 3 коротких пункта: главное, что нужно знать об этом обсуждении. Без вступлений.",
            "Give exactly 3 short bullets with the most important things about this discussion. No intro.",
        ),
        AnalysisKind(
            "digest", "Сводки", "Дайджест канала", "Channel digest",
            "Составь дайджест канала за период: главные посты и события, какие темы вызвали больше всего обсуждений и реакций, что изменилось в настроении аудитории. Формат: заголовок, 5–10 пунктов, вывод.",
            "Write a channel digest for the period: main posts and events, which topics drew the most discussion and reactions, how audience mood changed. Format: headline, 5-10 bullets, conclusion.",
            AnalysisScope.CHANNEL,
        ),
        AnalysisKind(
            "key_points", "Сводки", "Аргументы за и против", "Pros and cons",
            "Выпиши основные аргументы сторонников и противников (или позитивные и критические позиции), сгруппируй похожие, укажи, какие звучат чаще.",
            "List the main arguments of supporters and critics, group similar ones and indicate which are most frequent.",
        ),
        // --- Аудитория ---
        AnalysisKind(
            "sentiment", "Аудитория", "Тональность", "Sentiment",
            "Оцени тональность: примерные доли позитива, нейтрала и негатива, что вызывает одобрение и недовольство. Приведи 2–3 характерных примера своими словами.",
            "Assess sentiment: rough shares of positive, neutral and negative, what drives approval and dissatisfaction. Give 2-3 typical examples in your own words.",
        ),
        AnalysisKind(
            "questions", "Аудитория", "Вопросы аудитории (FAQ)", "Audience questions (FAQ)",
            "Найди вопросы, которые задаёт аудитория. Сгруппируй повторяющиеся, отсортируй по частоте и для каждого предложи краткий ответ от имени автора канала (если ответ следует из данных) или пометь «нужен ответ автора».",
            "Find the questions the audience asks. Group duplicates, sort by frequency, and draft a short answer for each from the channel author (if the data supports it) or mark it 'needs author's answer'.",
        ),
        AnalysisKind(
            "feedback", "Аудитория", "Боли, запросы, предложения", "Pain points & requests",
            "Извлеки обратную связь: боли и жалобы, запросы и пожелания, конкретные предложения. Для каждого пункта — суть, частота упоминаний, приоритет (высокий/средний/низкий).",
            "Extract feedback: pain points and complaints, wishes and requests, concrete suggestions. For each: gist, how often mentioned, priority (high/medium/low).",
        ),
        AnalysisKind(
            "persona", "Аудитория", "Портрет аудитории", "Audience persona",
            "По стилю и содержанию комментариев опиши портрет аудитории: интересы, уровень экспертизы, ценности, типичные мотивы комментирования, 2–3 условных сегмента. Не делай выводов о конкретных людях.",
            "From the style and content of comments describe the audience: interests, expertise level, values, typical motives for commenting, 2-3 rough segments. Do not profile specific individuals.",
        ),
        AnalysisKind(
            "top_comments", "Аудитория", "Лучшие комментарии", "Best comments",
            "Выбери 5–7 самых содержательных или ценных комментариев (с новой информацией, сильной аргументацией, полезным опытом). Для каждого — автор-псевдоним, пересказ своими словами и почему он ценен.",
            "Pick the 5-7 most insightful comments (new information, strong argument, useful experience). For each: author alias, paraphrase, and why it is valuable.",
        ),
        AnalysisKind(
            "conflicts", "Аудитория", "Конфликты и споры", "Conflicts",
            "Найди споры и конфликты в обсуждении: между кем (псевдонимы), о чём, насколько остро, кто прав по фактам, если это видно. Предложи, как модератору снизить градус.",
            "Find disputes and conflicts: between whom (aliases), about what, how heated, who is factually right if visible. Suggest how a moderator could de-escalate.",
        ),
        // --- Модерация ---
        AnalysisKind(
            "toxicity", "Модерация", "Токсичность и нарушения", "Toxicity",
            "Оцени уровень токсичности: оскорбления, угрозы, разжигание, травля, мат. Дай общую оценку 0–10, перечисли проблемные комментарии (псевдоним + суть) и рекомендации для модерации.",
            "Rate toxicity: insults, threats, hate, harassment, profanity. Give an overall 0-10 score, list problematic comments (alias + gist) and moderation recommendations.",
        ),
        AnalysisKind(
            "spam", "Модерация", "Спам и боты", "Spam & bots",
            "Найди признаки спама, рекламы, ботов и накруток: шаблонные тексты, ссылки, повторяющиеся сообщения, подозрительные авторы. Укажи степень уверенности для каждого случая.",
            "Find signs of spam, ads, bots and fake engagement: template texts, links, repeated messages, suspicious authors. Give a confidence level for each case.",
        ),
        // --- Контент и SEO ---
        AnalysisKind(
            "topics", "Контент и SEO", "Темы", "Topics",
            "Выдели 3–7 главных тем обсуждения. Для каждой: суть, позиции, насколько активно обсуждается.",
            "Identify the 3-7 main topics. For each: gist, positions taken, how actively discussed.",
        ),
        AnalysisKind(
            "content_ideas", "Контент и SEO", "Идеи следующих постов", "Next post ideas",
            "На основе того, что вызвало интерес, вопросы и споры, предложи 7–10 идей следующих постов: заголовок, формат (текст, опрос, разбор, видео), почему это зайдёт аудитории.",
            "Based on what drew interest, questions and disputes, propose 7-10 next post ideas: headline, format (text, poll, breakdown, video), why the audience will like it.",
        ),
        AnalysisKind(
            "seo", "Контент и SEO", "SEO и поиск в Telegram", "SEO & Telegram search",
            "Сделай SEO-анализ для поиска в Telegram и в поисковиках: ключевые слова и фразы, по которым аудитория ищет такой контент; 10–15 хэштегов; рекомендации по названию и описанию канала; как улучшить первые строки постов для превью и поиска.",
            "Do an SEO analysis for Telegram and web search: keywords and phrases people use to find this content; 10-15 hashtags; recommendations for the channel title and description; how to improve post first lines for previews and search.",
        ),
        AnalysisKind(
            "headlines", "Контент и SEO", "Заголовки и первые строки", "Headlines",
            "Предложи 10 вариантов цепляющего заголовка (первой строки) в разных стилях: информативный, вопрос, цифра, интрига, польза. Не используй кликбейт, искажающий смысл.",
            "Propose 10 catchy headline (first line) variants in different styles: informative, question, number, intrigue, benefit. No misleading clickbait.",
            AnalysisScope.POST,
        ),
        AnalysisKind(
            "trends", "Контент и SEO", "Тренды и упоминания", "Trends & mentions",
            "Выдели упоминаемые бренды, продукты, людей, организации, события и места. Для каждого — сколько раз упоминается и в каком тоне. Отметь, что набирает обороты.",
            "Extract mentioned brands, products, people, organizations, events and places. For each: how often and in what tone. Note what is gaining momentum.",
        ),
        AnalysisKind(
            "replies", "Контент и SEO", "Черновики ответов автора", "Reply drafts",
            "Выбери 5 комментариев, на которые автору канала стоит ответить в первую очередь, и предложи вежливый, полезный черновик ответа на каждый.",
            "Pick 5 comments the channel author should answer first and draft a polite, helpful reply for each.",
        ),
    )

    fun kind(id: String): AnalysisKind? = kinds.firstOrNull { it.id == id }

    fun forScope(isChannel: Boolean): List<AnalysisKind> = kinds.filter {
        it.scope == AnalysisScope.BOTH || (isChannel && it.scope == AnalysisScope.CHANNEL) || (!isChannel && it.scope == AnalysisScope.POST)
    }

    fun title(id: String, language: String): String {
        if (id == CUSTOM) return if (language == "ru") "Свой запрос" else "Custom prompt"
        val k = kind(id) ?: return id
        return if (language == "ru") k.titleRu else k.titleEn
    }

    /** System prompt: role, output rules and the task (or the user's own instruction). */
    fun system(id: String, language: String, customTask: String? = null): String {
        val frame = when (language) {
            "ru" -> "Ты аналитик сообщества Telegram-канала. Отвечай на русском, структурированно, в Markdown. Не выдумывай факты, опирайся только на данные. Авторы указаны псевдонимами."
            "zh" -> "You analyze a Telegram channel's community. Answer in Chinese (简体中文), structured, in Markdown. Do not invent facts. Authors are given as aliases."
            else -> "You analyze a Telegram channel's community. Answer in English, structured, in Markdown. Do not invent facts; rely only on the data. Authors are given as aliases."
        }
        val task = if (id == CUSTOM) {
            customTask?.takeIf { it.isNotBlank() } ?: "Analyze the discussion."
        } else {
            val k = kind(id) ?: kinds.first()
            if (language == "ru") k.taskRu else k.taskEn
        }
        return "$frame\n\n$task"
    }

    fun reduce(language: String): String = when (language) {
        "ru" -> "Ниже частичные анализы разных фрагментов одного обсуждения. Объедини их в один связный итоговый ответ того же формата, убрав повторы."
        "zh" -> "Below are partial analyses of chunks of one discussion. Merge them into one coherent final answer in Chinese without repetition."
        else -> "Below are partial analyses of different chunks of one discussion. Merge them into one coherent final answer in the same format without repetition."
    }
}
