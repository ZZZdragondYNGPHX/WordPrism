package dev.wordprism.contract

import kotlinx.serialization.Serializable

/** A connection draft: no saved preset, name or selected model is required. Never contains a secret. */
@Serializable data class ModelCatalogRequest(
    val baseUrl: String,
    val protocol: String = ApiProtocols.OPENAI_CHAT_COMPLETIONS,
    val timeoutSeconds: Int? = 45,
    /** Used only when no explicit secret is supplied; must still match the saved version and service root. */
    val credentialPreset: Ref? = null,
    val contractVersion: Int = Contract.VERSION,
)

/** Provider-supplied IDs, not a guarantee of text/JSON or protocol compatibility. */
@Serializable data class ApiModel(val id: String, val ownedBy: String? = null)
