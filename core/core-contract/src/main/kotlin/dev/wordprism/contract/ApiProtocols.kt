package dev.wordprism.contract

/** Stable protocol IDs and the installed adapter catalog. Clients read this via Query.Protocols. */
object ApiProtocols {
    const val OPENAI_CHAT_COMPLETIONS = "openai_chat_completions"
    const val OPENAI_RESPONSES = "openai_responses"

    val supported: List<ProtocolInfo> = listOf(
        ProtocolInfo(OPENAI_CHAT_COMPLETIONS, "OpenAI Chat Completions", "https://api.openai.com/v1",
            "HTTPS Chat Completions；可填 API 根地址或完整调用地址，模型需支持文本 JSON 输出", ReasoningEffort.entries),
        ProtocolInfo(OPENAI_RESPONSES, "OpenAI Responses", "https://api.openai.com/v1",
            "原生 Responses；可填 API 根地址或完整调用地址，输出上限包含推理 tokens", ReasoningEffort.entries),
    )
}
