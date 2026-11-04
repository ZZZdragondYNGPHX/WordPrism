package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.serialization.json.*

internal fun Database.businessEffects(operationId: Id): List<BusinessEffect> {
    val ids = linkedSetOf(operationId); val pending = ArrayDeque<Id>(); pending.add(operationId)
    while (pending.isNotEmpty()) {
        val parent = pending.removeFirst()
        operations.values.filter { it.parentOperationId == parent }.forEach { if (ids.add(it.id)) pending.add(it.id) }
    }
    val fieldLabels = mapOf("notes" to "笔记", "tags" to "标签", "archived" to "归档状态", "meaning" to "释义", "examples" to "例句",
        "acceptedForms" to "有效答案", "title" to "名称", "active" to "成员状态", "reason" to "归组依据", "suspended" to "学习暂停", "presetId" to "预设", "text" to "内容", "disputed" to "争议标记", "explanation" to "词源与助记讲解")
    return ids.flatMap { opId ->
        val op = operations.getValue(opId)
        undos[opId].orEmpty().flatMap { entry ->
            val after = entry.after as? JsonObject ?: return@flatMap emptyList()
            val before = entry.before as? JsonObject
            val name = when (entry.bucket) {
                "words" -> after["spelling"]?.jsonPrimitive?.content
                "senses" -> senses[Id(entry.key)]?.wordId?.let { words[it]?.spelling }
                "groups" -> after["title"]?.jsonPrimitive?.content
                "memberships" -> memberships[Id(entry.key)]?.let { m -> "${groups[m.groupId]?.title.orEmpty()} · ${words[senses[m.senseId]?.wordId]?.spelling.orEmpty()}" }
                else -> null
            } ?: entry.key
            after.keys.filter { it in fieldLabels && before?.get(it) != after[it] }.map { field ->
                BusinessEffect(opId, op.kind, entry.key, "$name · ${fieldLabels.getValue(field)}", field, before?.get(field), after.getValue(field),
                    field in setOf("suspended", "disputed"))
            }
        }
    }
}
