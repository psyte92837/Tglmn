package dev.tgscope.domain.llm

/** Built-in analysis prompts in Russian, English and Chinese, like the original `prompts/llm_*.json`. */
object Prompts {
    val languages = listOf("ru", "en", "zh")
    val names = listOf("summary", "sentiment", "topics")

    fun title(name: String, language: String): String = when (language) {
        "ru" -> mapOf("summary" to "Сводка", "sentiment" to "Тональность", "topics" to "Темы и спорные моменты")
        "zh" -> mapOf("summary" to "摘要", "sentiment" to "情感分析", "topics" to "主题与争议")
        else -> mapOf("summary" to "Summary", "sentiment" to "Sentiment", "topics" to "Topics and controversies")
    }[name] ?: name

    fun system(name: String, language: String): String {
        val task = when (language) {
            "ru" -> when (name) {
                "sentiment" -> "Оцени тональность обсуждения: доли позитива, нейтрала и негатива, что вызывает недовольство и одобрение. Приведи 2–3 коротких характерных примера своими словами."
                "topics" -> "Выдели 3–7 главных тем обсуждения и спорные моменты. Для каждой темы: суть, позиции сторон, насколько активно обсуждается."
                else -> "Сделай краткую сводку обсуждения: о чём пост, как отреагировала аудитория, ключевые мнения и вопросы, общий вывод."
            }
            "zh" -> when (name) {
                "sentiment" -> "评估讨论的情感：正面、中性、负面的大致比例，引起不满和赞同的原因。用你自己的话给出2-3个典型例子。"
                "topics" -> "找出讨论中的3-7个主要话题和争议点。每个话题说明要点、各方立场以及讨论热度。"
                else -> "简要总结讨论：帖子内容、受众反应、主要观点和问题，以及总体结论。"
            }
            else -> when (name) {
                "sentiment" -> "Assess the sentiment of the discussion: rough shares of positive, neutral and negative, what drives approval and dissatisfaction. Give 2-3 short typical examples in your own words."
                "topics" -> "Identify the 3-7 main topics and controversies. For each: the gist, the positions taken, and how actively it is discussed."
                else -> "Write a concise summary of the discussion: what the post is about, how the audience reacted, key opinions and questions, overall takeaway."
            }
        }
        val frame = when (language) {
            "ru" -> "Ты аналитик сообщества Telegram-канала. Отвечай на русском, структурированно, в Markdown. Не выдумывай факты, опирайся только на данные."
            "zh" -> "你是Telegram频道社区分析师。请用中文、以Markdown结构化回答。不要编造事实，只依据所给数据。"
            else -> "You analyze a Telegram channel's community. Answer in English, structured, in Markdown. Do not invent facts; rely only on the data."
        }
        return "$frame\n\n$task"
    }

    fun reduce(language: String): String = when (language) {
        "ru" -> "Ниже частичные анализы разных фрагментов одного обсуждения. Объедини их в один связный итоговый ответ, убрав повторы."
        "zh" -> "以下是同一讨论不同片段的部分分析。请将它们合并为一个连贯的最终答案，去除重复。"
        else -> "Below are partial analyses of different chunks of one discussion. Merge them into one coherent final answer without repetition."
    }
}
