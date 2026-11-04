package dev.wordprism.engine

import dev.wordprism.contract.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class PackageTest {
    @Test fun `invalid policy broken references and unknown mandatory modules cannot replace the library`() = runBlocking {
        val h = Harness(); h.seed(); h.answer()
        val original = h.store.read().answers
        val buffer = ByteArrayOutputStream(); assertNull(h.core.exportPackage(PackagePurpose.BACKUP, buffer).fault)
        val badPolicy = rewrite(buffer.toByteArray()) { entries ->
            val module = entries.getValue("modules/memory.json").jsonObject
            val policy = module.getValue("policy").jsonObject
            entries["modules/memory.json"] = JsonObject(module + ("policy" to JsonObject(policy + ("mix" to json("{\"FAMILIARITY\":100}")))))
        }
        assertEquals(ErrorCode.INVALID_PACKAGE, h.core.previewPackage(ByteArrayInputStream(badPolicy)).fault?.code)
        val badReference = rewrite(buffer.toByteArray()) { entries ->
            val module = entries.getValue("modules/library.json").jsonObject
            entries["modules/library.json"] = JsonObject(module + ("targets" to JsonObject(emptyMap())))
        }
        assertEquals(ErrorCode.INVALID_PACKAGE, h.core.previewPackage(ByteArrayInputStream(badReference)).fault?.code)
        val required = rewrite(buffer.toByteArray()) { entries ->
            entries["modules/future.json"] = json("{}")
            val manifest = Contract.json.decodeFromJsonElement<PackageManifest>(entries.getValue("manifest.json"))
            entries["manifest.json"] = Contract.json.encodeToJsonElement(manifest.copy(modules = manifest.modules + ModuleManifest("future", 1, "modules/future.json", "", true)))
        }
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, h.core.previewPackage(ByteArrayInputStream(required)).fault?.code)
        assertEquals(original, h.store.read().answers)
    }
    private fun rewrite(bytes: ByteArray, change: (MutableMap<String, JsonElement>) -> Unit): ByteArray {
        val entries = linkedMapOf<String, JsonElement>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = Contract.json.parseToJsonElement(zip.readBytes().toString(Charsets.UTF_8)) }
        }
        change(entries)
        val manifest = Contract.json.decodeFromJsonElement<PackageManifest>(entries.getValue("manifest.json"))
        entries["manifest.json"] = Contract.json.encodeToJsonElement(manifest.copy(modules = manifest.modules.map { it.copy(sha256 = sha256(entries.getValue(it.path).toString().toByteArray())) }))
        val output = ByteArrayOutputStream(); ZipOutputStream(output).use { zip -> entries.forEach { (name,value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toString().toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }
    @Test fun `migration and unknown optional fields modules survive reexport`() = runBlocking {
        val h = Harness(); h.seed(); val original = ByteArrayOutputStream(); h.core.exportPackage(PackagePurpose.BACKUP, original)
        val future = rewrite(original.toByteArray()) { entries ->
            val library = entries.getValue("modules/library.json").jsonObject
            val words = library.getValue("words").jsonObject
            val key = words.keys.first()
            entries["modules/library.json"] = JsonObject(library + ("words" to JsonObject(words + (key to JsonObject(words.getValue(key).jsonObject + ("futureField" to JsonPrimitive("keep-me")))))))
            entries["modules/future.json"] = json("""{"archivedFeature":{"value":42}}""")
            val manifest = Contract.json.decodeFromJsonElement<PackageManifest>(entries.getValue("manifest.json"))
            entries["manifest.json"] = Contract.json.encodeToJsonElement(manifest.copy(modules = manifest.modules.map { it.copy(schemaVersion = 1) } + ModuleManifest("future", 1, "modules/future.json", "", false, listOf("library"))))
        }
        val restored = Harness(); val preview = restored.core.previewPackage(ByteArrayInputStream(future))
        assertNull(preview.fault, preview.fault.toString()); val details = (preview.value as View.ImportPreview).preview
        assertTrue(details.migrations.contains("library:1->2")); assertEquals(listOf("future"), details.archivedModules)
        restored.command(Command.RestoreBackup(details.token))
        val word = restored.store.read().words.values.first(); restored.command(Command.UpdateWord(Ref(word.id,word.version), "edited", emptySet()))
        val output = ByteArrayOutputStream(); restored.core.exportPackage(PackagePurpose.BACKUP, output)
        var found = false
        rewrite(output.toByteArray()) { entries ->
            assertEquals(json("""{"archivedFeature":{"value":42}}"""), entries["modules/future.json"])
            found = entries.getValue("modules/library.json").toString().contains("keep-me")
        }
        assertTrue(found)
    }
    @Test fun `full backup restores history and policy audit without credentials or automatic work`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val marker = java.util.UUID.randomUUID().toString()
        val presetId = h.store.read().presets.keys.single()
        h.core.setCredential(presetId, marker.toCharArray())
        h.clock.advance(30_000); h.command(Command.SetTimer(h.store.read().sessions.keys.single(), TimerMode.PAUSED))
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("newTargetLimit" to json("3")), "test"), 1))
        val pending = h.command(Command.SubmitIntake("linen")) as View.OperationResult
        val output = ByteArrayOutputStream(); assertNull(h.core.exportPackage(PackagePurpose.BACKUP, output).fault)
        rewrite(output.toByteArray()) { entries ->
            assertFalse(entries.values.any { it.toString().contains(marker) || it.toString().contains("credentialRefs") })
        }
        val restored = Harness(); restored.configure()
        val preview = restored.core.previewPackage(ByteArrayInputStream(output.toByteArray()))
        assertNull(preview.fault, preview.fault.toString()); val token = (preview.value as View.ImportPreview).preview.token
        restored.command(Command.RestoreBackup(token), "restore-once")
        val original = h.store.read(); val after = restored.store.read()
        assertEquals(original.words, after.words); assertEquals(original.answers, after.answers); assertEquals(original.schedules, after.schedules)
        assertEquals(original.changes, after.changes); assertEquals(original.bindings, after.bindings); assertEquals(original.timeSpans, after.timeSpans)
        assertTrue(after.credentialRefs.isEmpty())
        assertTrue((restored.core.query(Query.Presets).value as View.Presets).presets.none { it.hasCredential })
        assertFalse(after.settings.automaticAnalysis); assertEquals(OperationStatus.RESTORED_PENDING, after.operations[pending.operation.id]!!.status)
        restored.core.tasks.runPending(true); assertTrue(restored.provider.requests.isEmpty())
        restored.core.setCredential(presetId, java.util.UUID.randomUUID().toString().toCharArray())
        restored.command(Command.RestoreBackup(token), "restore-once"); assertEquals(original.answers.size, restored.store.read().answers.size)
        assertTrue((restored.core.query(Query.Presets).value as View.Presets).presets.single().hasCredential)
    }
    @Test fun `preview cannot overwrite learning that happened afterwards`() = runBlocking {
        val h = Harness(); h.seed(); h.answer()
        val bytes = ByteArrayOutputStream(); h.core.exportPackage(PackagePurpose.BACKUP, bytes)
        val preview = h.core.previewPackage(ByteArrayInputStream(bytes.toByteArray())).value as View.ImportPreview
        h.answer(); val afterLearning = h.store.read().answers
        val result = h.core.execute(Request("stale-restore", Command.RestoreBackup(preview.preview.token)))
        assertEquals(ErrorCode.VERSION_CONFLICT, result.fault?.code); assertEquals(afterLearning, h.store.read().answers)
    }
    @Test fun `content package has no personal progress and reimport does not duplicate`() = runBlocking {
        val h = Harness(); h.seed(); h.answer()
        val bytes = ByteArrayOutputStream(); h.core.exportPackage(PackagePurpose.CONTENT, bytes)
        val restored = Harness()
        repeat(2) { index ->
            val preview = restored.core.previewPackage(ByteArrayInputStream(bytes.toByteArray()))
            assertNull(preview.fault, preview.fault.toString())
            restored.command(Command.CommitImport((preview.value as View.ImportPreview).preview.token), "import-$index")
        }
        assertEquals(2, restored.store.read().words.size); assertTrue(restored.store.read().answers.isEmpty())
        assertTrue(restored.store.read().schedules.values.all { it.reviews == 0 })
    }
    @Test fun `zip traversal and damaged modules leave current library unchanged`() = runBlocking {
        val h = Harness(); h.seed(); val original = h.store.read().words
        val bad = ByteArrayOutputStream(); ZipOutputStream(bad).use { it.putNextEntry(ZipEntry("../escape")); it.write(byteArrayOf(1)); it.closeEntry() }
        val result = h.core.previewPackage(ByteArrayInputStream(bad.toByteArray()))
        assertEquals(ErrorCode.INVALID_PACKAGE, result.fault?.code); assertEquals(original, h.store.read().words)
    }
}
