package app.wordprism.mock

/**
 * Canned "AI" content so the mock can answer intake / generation requests offline and deterministically.
 * This is NOT the backend's content model and has no claim to lexical accuracy beyond a demo.
 */
internal data class LexEntry(
    val lemma: String,
    val pos: String,
    val glossZh: String,
    val glossEn: String,
    val collocations: List<String>,
    val example: String,
    val exampleZh: String,
    val related: List<String> = emptyList(),
)

internal object MockLexicon {
    private val entries = listOf(
        LexEntry("linear", "adj.", "线性的；直线的", "arranged in or extending along a straight line", listOf("linear growth", "linear equation"), "The data show a linear increase over time.", "数据显示随时间呈线性增长。", listOf("lineal", "linen")),
        LexEntry("liner", "n.", "班轮；衬里，内衬", "a large passenger ship; a lining", listOf("ocean liner", "bin liner"), "They crossed the Atlantic on a liner.", "他们乘班轮横渡大西洋。", listOf("lining")),
        LexEntry("linen", "n.", "亚麻布；床单桌布类织品", "cloth woven from flax; household linens", listOf("bed linen", "linen shirt"), "She changed the bed linen every week.", "她每周换一次床单。", listOf("lining")),
        LexEntry("lineal", "adj.", "直系的（血统）", "in a direct line of descent", listOf("lineal descendant"), "He is a lineal descendant of the founder.", "他是创始人的直系后代。"),
        LexEntry("lining", "n.", "衬里；内层", "a layer of material covering the inside", listOf("silver lining", "coat lining"), "The coat has a silk lining.", "这件外套有丝绸衬里。"),
        LexEntry("adapt", "v.", "使适应；改编", "to change to suit new conditions", listOf("adapt to", "adapt a novel"), "Children adapt quickly to new schools.", "孩子们很快适应新学校。", listOf("adept")),
        LexEntry("adopt", "v.", "采用；收养", "to take up and use; to legally take a child", listOf("adopt a policy", "adopt a child"), "The committee adopted the new rules.", "委员会采纳了新规定。", listOf("adept")),
        LexEntry("adept", "adj.", "熟练的；擅长的", "very skilled or proficient", listOf("adept at"), "She is adept at solving puzzles.", "她擅长解谜。"),
        LexEntry("affect", "v.", "影响", "to have an influence on", listOf("affect health"), "Lack of sleep affects concentration.", "睡眠不足会影响注意力。", listOf("effect")),
        LexEntry("effect", "n.", "效果；影响", "a change that results from an action", listOf("side effect", "take effect"), "The new law takes effect next month.", "新法律下月生效。"),
        LexEntry("principal", "n./adj.", "校长；主要的", "head of a school; most important", listOf("school principal", "principal reason"), "The principal reason is cost.", "主要原因是成本。", listOf("principle")),
        LexEntry("principle", "n.", "原则；原理", "a fundamental truth or rule", listOf("in principle", "basic principle"), "It is against my principles.", "这违背我的原则。"),
        LexEntry("economic", "adj.", "经济（学）的", "relating to the economy", listOf("economic growth"), "The country faces economic problems.", "这个国家面临经济问题。", listOf("economical")),
        LexEntry("economical", "adj.", "节约的；划算的", "giving good value; not wasteful", listOf("economical car"), "This heater is very economical to run.", "这台取暖器运行很省钱。"),
        LexEntry("complement", "n./v.", "补充；使完整", "something that completes or goes well with", listOf("complement each other"), "The wine complements the meal.", "这酒与菜很相配。", listOf("compliment")),
        LexEntry("compliment", "n./v.", "称赞", "an expression of praise", listOf("pay a compliment"), "She paid me a compliment on my essay.", "她称赞了我的文章。"),
        LexEntry("stationary", "adj.", "静止的", "not moving", listOf("remain stationary"), "The car was stationary at the lights.", "车停在红绿灯前不动。", listOf("stationery")),
        LexEntry("stationery", "n.", "文具", "writing materials", listOf("office stationery"), "We ordered more stationery.", "我们又订购了文具。"),
        LexEntry("desert", "n./v.", "沙漠；抛弃", "a dry barren area; to abandon", listOf("desert island"), "They crossed the desert by camel.", "他们骑骆驼穿越沙漠。", listOf("dessert")),
        LexEntry("dessert", "n.", "甜点", "the sweet course at the end of a meal", listOf("for dessert"), "We had ice cream for dessert.", "我们甜点吃了冰淇淋。"),
        LexEntry("say", "v.", "说（内容）", "to utter words", listOf("say that", "say hello"), "He said that he was tired.", "他说他累了。", listOf("tell", "speak")),
        LexEntry("tell", "v.", "告诉（某人）", "to communicate information to someone", listOf("tell sb sth", "tell the truth"), "Tell me what happened.", "告诉我发生了什么。"),
        LexEntry("speak", "v.", "讲（语言）；发言", "to talk; to use a language", listOf("speak English"), "She speaks three languages.", "她会说三种语言。"),
    ).associateBy { it.lemma }

    fun get(lemma: String): LexEntry? = entries[lemma.lowercase()]

    /** Generic placeholder content for words the demo lexicon does not know. */
    fun placeholder(lemma: String) = LexEntry(
        lemma = lemma, pos = "—", glossZh = "（模拟生成）$lemma 的释义待接入真实 AI",
        glossEn = "mock gloss", collocations = emptyList(),
        example = "This is a mock example with $lemma.", exampleZh = "这是包含 $lemma 的模拟例句。",
    )

    fun entryOrPlaceholder(lemma: String) = get(lemma) ?: placeholder(lemma)

    fun closestKnown(word: String): String? =
        entries.keys.filter { it != word && editDistance(it, word) == 1 && get(word) == null }.minOrNull()

    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /**
     * Mock letter alignment: pads lemmas to a common column layout by aligning on the longest
     * common prefix, and marks the characters that differ from the first member.
     */
    fun alignAndHighlight(lemmas: List<String>): List<Pair<String, List<IntRange>>> {
        if (lemmas.isEmpty()) return emptyList()
        val width = lemmas.maxOf { it.length }
        return lemmas.map { lemma ->
            val others = lemmas.filter { it != lemma }
            val ranges = mutableListOf<IntRange>()
            var start = -1
            for (i in lemma.indices) {
                val differs = others.any { o -> i >= o.length || o[i] != lemma[i] }
                if (differs && start < 0) start = i
                if (!differs && start >= 0) { ranges += start until i; start = -1 }
            }
            if (start >= 0) ranges += start until lemma.length
            lemma.padEnd(width) to ranges
        }
    }
}
