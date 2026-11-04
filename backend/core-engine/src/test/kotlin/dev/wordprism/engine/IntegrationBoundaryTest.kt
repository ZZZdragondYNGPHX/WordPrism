package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class IntegrationBoundaryTest {
    @Test fun `plan counts distinct new words and exposures show learning without inventing memory reviews`() = runBlocking<Unit> {
        val h = Harness(); h.seed()
        val day = h.core.query(Query.GetLearningDay()).value as View.Day
        assertEquals(2, day.summary.plannedNewWords)
        assertTrue(day.plan!!.newCount > day.summary.plannedNewWords)
        val started = h.command(Command.StartSession(includeIntroductions = true)) as View.SessionState
        val question = h.command(Command.NextActivity(started.session.id)) as View.SessionState
        assertEquals(ActivityKind.FAMILIARITY, question.activity!!.kind)
        h.answer(question, AnswerInput("", familiar = true))
        val group = (h.core.query(Query.LibrarySummary()).value as View.LibrarySummary).library.groups.single()
        assertEquals(StudyState.LEARNING, group.study.state)
        assertEquals(0, h.store.read().schedules.values.sumOf { it.reviews })
        assertEquals(2, (h.core.query(Query.GetLearningDay()).value as View.Day).summary.plannedNewWords)
        // Missing legacy fields use historical facts, not today's mutable scheduler state.
        h.store.transaction { db -> db.plans[day.plan!!.id] = day.plan!!.copy(newWordCount = null) }
        assertEquals(2, (h.core.query(Query.GetLearningDay()).value as View.Day).summary.plannedNewWords)
    }

    @Test fun `closing yesterday does not pause a resumed session today and edits retain historical plans`() = runBlocking<Unit> {
        val h = Harness(); h.seed(); val answer = h.answer()
        val before = h.store.read(); val yesterday = before.days.values.single(); val oldPlan = before.plans.getValue(yesterday.planId!!)
        h.clock.advance(DAY_MS)
        h.command(Command.SetTimer(answer.answer.sessionId, TimerMode.STUDY))
        assertNull(h.core.query(Query.GetLearningDay()).fault)
        h.command(Command.FinishLearningDay(yesterday.id))
        assertEquals(SessionStatus.ACTIVE, h.store.read().sessions.getValue(answer.answer.sessionId).status)
        val word = h.store.read().words.values.first()
        h.command(Command.UpdateWord(Ref(word.id, word.version), "current note", emptySet()))
        val today = h.core.query(Query.GetLearningDay()).value as View.Day
        val after = h.store.read()
        assertEquals(oldPlan.id, after.days.getValue(yesterday.id).planId)
        assertEquals(oldPlan, after.plans.getValue(oldPlan.id)); assertNotNull(today.plan)
    }

    @Test fun `preset form edits preserve advanced options and replay remains stable`() = runBlocking {
        val h = Harness(); h.configure(false)
        val original = h.store.read().presets.values.single()
        h.command(Command.SavePreset(original.copy(enabled = false, jsonMode = false, temperature = 0.4f), original.version))
        val before = h.store.read().presets.values.single()
        val command = Command.SavePreset(ApiPreset(before.id, name = "renamed", baseUrl = before.baseUrl, model = before.model, reasoningEffort = ReasoningEffort.HIGH), before.version, preserveAdvancedSettings = true)
        h.command(command, "form-once"); h.command(command, "form-once")
        val after = h.store.read().presets.values.single()
        assertFalse(after.enabled); assertFalse(after.jsonMode); assertEquals(0.4f, after.temperature)
        assertEquals(ReasoningEffort.HIGH, after.reasoningEffort)
        assertEquals(before.version + 1, after.version)
        val clear = Command.SavePreset(after.copy(reasoningEffort = null), after.version, preserveAdvancedSettings = true)
        h.command(clear, "clear-effort"); h.command(clear, "clear-effort")
        assertNull(h.store.read().presets.getValue(after.id).reasoningEffort)
        assertEquals(after.version + 1, h.store.read().presets.getValue(after.id).version)
        assertEquals(ReasoningEffort.HIGH, h.store.read().presetVersions.getValue("${after.id.value}:${after.version}").reasoningEffort)
    }

    @Test fun `legacy rows without reasoning effort decode as provider default including queued snapshots`() = runBlocking {
        val h = Harness(); h.configure(); h.command(Command.SubmitIntake("linear"))
        fun withoutEffort(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it != "reasoningEffort" }.mapValues { withoutEffort(it.value) })
            is JsonArray -> JsonArray(value.map { withoutEffort(it) })
            else -> value
        }
        val original = h.store.read()
        val legacy = StateCodec.rows(original).map { it.copy(payload = withoutEffort(json(it.payload)).toString()) }
        val loaded = StateCodec.decode(legacy)
        assertEquals(original, loaded)
        assertNull(loaded.presets.values.single().reasoningEffort)
        assertNull(loaded.operations.values.single().preset!!.reasoningEffort)
        assertFailsWith<kotlinx.serialization.SerializationException> {
            Contract.json.decodeFromString<ApiPreset>("""{"id":"bad","name":"invalid","baseUrl":"https://example.invalid/v1","model":"test","reasoningEffort":"arbitrary"}""")
        }
    }

    @Test fun `upgrading preserves legacy preset request identities while explicit effort remains distinct`() = runBlocking {
        // The test.3 command representation predates reasoningEffort; its hash must survive an upgrade.
        val legacyJson = """{"type":"save_preset","preset":{"id":"fixture-responses-preset","version":1,"name":"Responses 示例","baseUrl":"https://api.openai.com/v1","model":"your-responses-model","protocol":"openai_responses","enabled":true,"timeoutSeconds":45,"maxOutputTokens":4096,"jsonMode":true,"temperature":null},"expectedVersion":0,"preserveAdvancedSettings":false}"""
        val command = Contract.json.decodeFromString<Command>(legacyJson) as Command.SavePreset
        val h = Harness()
        h.command(command, "legacy-preset")
        h.store.transaction { db ->
            db.requests["legacy-preset"] = db.requests.getValue("legacy-preset").copy(fingerprint = sha256(legacyJson.toByteArray()))
        }
        val before = h.store.read()
        val reopened = CoreEngine(h.store, h.credentials, h.memory, h.clock, h.provider)
        assertNull(reopened.execute(Request("legacy-preset", command)).fault)
        assertEquals(before, h.store.read())
        assertEquals(legacyJson, Contract.json.encodeToString<Command>(command))

        val changed = command.copy(preset = command.preset.copy(reasoningEffort = ReasoningEffort.HIGH))
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, reopened.execute(Request("legacy-preset", changed)).fault?.code)
        val update = changed.copy(expectedVersion = 1)
        assertNull(reopened.execute(Request("explicit-effort", update)).fault)
        val updated = h.store.read()
        assertNull(reopened.execute(Request("explicit-effort", update)).fault)
        assertEquals(updated, h.store.read())
        assertEquals(ReasoningEffort.HIGH, updated.presets.getValue(command.preset.id).reasoningEffort)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, reopened.execute(Request("explicit-effort", update.copy(preset = command.preset))).fault?.code)
    }

    @Test fun `appending group members retains order after row codec reload`() = runBlocking {
        val h = Harness(); h.seed()
        val group = h.store.read().groups.values.single()
        val before = (h.core.query(Query.GetGroup(group.id)).value as View.Library).words.map { it.spelling }
        assertEquals(listOf("linear", "liner"), before)
        h.command(Command.SaveWords(listOf(WordSeed("linen", "亚麻布")), existingGroup = Ref(group.id, group.version)))
        val restored = StateCodec.decode(StateCodec.rows(h.store.read()).sortedBy { it.key })
        assertEquals(before + "linen", restored.groupWords(group.id).map { it.spelling })
    }

    @Test fun `undoing old binding cannot resurrect a deleted preset`() = runBlocking {
        val h = Harness(); h.configure(false)
        val first = h.store.read().presets.values.single()
        val second = first.copy(id = Id("second"), name = "second", version = 1)
        h.command(Command.SavePreset(second))
        val change = h.command(Command.BindPreset(Binding(Feature.WORD_INTAKE, second.id))) as View.OperationResult
        h.command(Command.DeletePreset(Ref(first.id, first.version)))
        val result = h.core.execute(Request("undo-removed-preset", Command.UndoOperation(change.operation.id)))
        assertEquals(ErrorCode.VERSION_CONFLICT, result.fault?.code)
        assertEquals(second.id, h.store.read().bindings.getValue(Feature.WORD_INTAKE).presetId)
    }

    @Test fun `bounded fitting selection retains first delayed prefixes without resetting histories`() = runBlocking {
        val h = Harness(); h.seed(); val original = h.answer().answer
        val db = h.store.read(); db.answers.clear(); var sequence = 0L
        repeat(30) { target -> repeat(220) { day ->
            val id = Id("sample-${++sequence}")
            db.answers[id] = original.copy(id = id, targetId = Id("target-$target"), sequence = sequence,
                atMs = day * DAY_MS + target, rating = Rating.GOOD, valid = true, affectsSchedule = true)
        } }
        val selected = db.histories()
        assertTrue(selected.size in 2500..5000)
        assertTrue(selected.all { it.history.reviews.first().delta_t == 0 && it.history.reviews.size <= 256 })
        selected.groupBy { it.targetId }.forEach { (_, samples) ->
            assertTrue(samples.any { it.history.reviews.count { review -> review.delta_t > 0 } == 1 })
        }
    }
}
