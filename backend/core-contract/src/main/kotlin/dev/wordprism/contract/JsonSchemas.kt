@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package dev.wordprism.contract

import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Schemas are derived from the same descriptors used by the SDK; no parallel model. */
object JsonSchemas {
    fun document(descriptor: SerialDescriptor, title: String, rootFields: Set<String>? = null, variants: Set<String>? = null): JsonObject {
        val definitions = linkedMapOf<String, JsonElement>()
        fun describe(current: SerialDescriptor, discriminator: String? = null, root: Boolean = false): JsonElement {
            val schema: JsonElement = when {
                current.serialName.startsWith("kotlinx.serialization.json.") -> JsonObject(emptyMap())
                current.isInline -> describe(current.getElementDescriptor(0))
                current.kind == PrimitiveKind.STRING || current.kind == PrimitiveKind.CHAR -> buildJsonObject { put("type", "string") }
                current.kind == PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
                current.kind is PrimitiveKind -> buildJsonObject { put("type", if (current.kind == PrimitiveKind.FLOAT || current.kind == PrimitiveKind.DOUBLE) "number" else "integer") }
                current.kind == SerialKind.ENUM -> buildJsonObject {
                    put("type", "string"); put("enum", JsonArray((0 until current.elementsCount).map { JsonPrimitive(current.getElementName(it)) }))
                }
                current.kind == StructureKind.LIST -> buildJsonObject { put("type", "array"); put("items", describe(current.getElementDescriptor(0))) }
                current.kind == StructureKind.MAP -> buildJsonObject { put("type", "object"); put("additionalProperties", describe(current.getElementDescriptor(1))) }
                current.kind == PolymorphicKind.SEALED -> {
                    val union = current.getElementDescriptor(1)
                    val members = (0 until union.elementsCount).map { union.getElementDescriptor(it) }.filter { !root || variants == null || it.serialName in variants }
                    require(members.isNotEmpty()) { "No matching sealed variants" }
                    buildJsonObject { put("oneOf", JsonArray(members.map { describe(it, it.serialName) })) }
                }
                else -> {
                    val key = current.serialName.removeSuffix("?") + if (discriminator != null) "-variant" else ""
                    if (key !in definitions) {
                        definitions[key] = JsonObject(emptyMap())
                        val properties = linkedMapOf<String, JsonElement>(); val required = mutableListOf<JsonElement>()
                        if (discriminator != null) { properties["type"] = buildJsonObject { put("const", discriminator) }; required += JsonPrimitive("type") }
                        repeat(current.elementsCount) { index ->
                            val field = current.getElementName(index)
                            if (!root || rootFields == null || field in rootFields) {
                                properties[field] = describe(current.getElementDescriptor(index))
                                if (!current.isElementOptional(index)) required += JsonPrimitive(field)
                            }
                        }
                        definitions[key] = buildJsonObject {
                            put("type", "object"); put("properties", JsonObject(properties)); put("required", JsonArray(required)); put("additionalProperties", false)
                        }
                    }
                    buildJsonObject { put("\$ref", "#/\$defs/" + key.replace("~", "~0").replace("/", "~1")) }
                }
            }
            return if (current.isNullable) buildJsonObject { put("anyOf", JsonArray(listOf(schema, buildJsonObject { put("type", "null") }))) } else schema
        }
        val root = describe(descriptor, root = true)
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema"); put("title", title)
            put("allOf", JsonArray(listOf(root))); put("\$defs", JsonObject(definitions))
        }
    }
}
