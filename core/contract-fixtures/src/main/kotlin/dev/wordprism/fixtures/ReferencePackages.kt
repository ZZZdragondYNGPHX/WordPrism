package dev.wordprism.fixtures

import dev.wordprism.contract.*
import dev.wordprism.engine.CoreEngine
import dev.wordprism.engine.CredentialStore
import dev.wordprism.jvm.SqliteStateStore
import dev.wordprism.memory.NativeMemoryKernel
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Public synthetic examples, exported and restored by the real engine; no model requests. */
internal suspend fun writeReferencePackages(directory: File) {
    directory.mkdirs()
    val folder = Files.createTempDirectory("wordprism-package-example-")
    val credentials = object : CredentialStore {
        override fun has(presetId: Id) = false
        override fun read(presetId: Id): CharArray? = null
        override fun write(presetId: Id, secret: CharArray) { error("Examples never configure credentials") }
        override fun delete(presetId: Id) = Unit
    }
    val clock = Clock.fixed(Instant.parse("2026-11-03T18:00:00Z"), ZoneId.of("UTC"))
    try {
        SqliteStateStore(folder.resolve("source.db")).use { store ->
            val core = CoreEngine(store, credentials, NativeMemoryKernel(), clock)
            suspend fun execute(key: String, command: Command) = core.execute(Request(key, command)).also { check(it.fault == null) { it.fault.toString() } }.value!!
            execute("example-words", Command.SaveWords(listOf(
                WordSeed("linear", "线性的；按顺序发展的", "adjective", examples = listOf("The graph shows a linear relationship.")),
                WordSeed("liner", "衬里；大型客轮", "noun", examples = listOf("The jacket has a removable liner.")),
                WordSeed("linen", "亚麻布；亚麻织品", "noun", examples = listOf("The shirt is made of linen."))),
                GroupSeed("linear / liner / linen", Relation.LOOK_ALIKE, "合成参考包：比较字母结构和具体词义")))
            val content = File(directory, "example-content.wordprism")
            content.outputStream().use { check(core.exportPackage(PackagePurpose.CONTENT, it).fault == null) }
            val session = (execute("example-start", Command.StartSession()) as View.SessionState).session
            repeat(2) { number ->
                val presentation = execute("example-next-$number", Command.NextActivity(session.id)) as View.SessionState
                val activity = store.read().activities.getValue(presentation.activity!!.id)
                execute("example-answer-$number", Command.SubmitAnswer(session.id, presentation.session.currentPresentationId!!, Ref(activity.id, activity.version),
                    AnswerInput(activity.acceptedAnswers.firstOrNull() ?: "This synthetic example uses a linear structure.")))
            }
            execute("example-pause", Command.FinishSession(session.id, pause = true))
            val backup = File(directory, "example-backup.wordprism")
            backup.outputStream().use { check(core.exportPackage(PackagePurpose.BACKUP, it).fault == null) }
            for ((file, isBackup) in listOf(content to false, backup to true)) SqliteStateStore(folder.resolve("verify-${file.name}.db")).use { target ->
                val restored = CoreEngine(target, credentials, NativeMemoryKernel(), clock)
                val preview = file.inputStream().use { restored.previewPackage(it) }
                check(preview.fault == null) { preview.fault.toString() }
                val token = (preview.value as View.ImportPreview).preview.token
                val result = restored.execute(Request("verify", if (isBackup) Command.RestoreBackup(token) else Command.CommitImport(token)))
                check(result.fault == null) { result.fault.toString() }
                check(target.read().words.size == 3 && target.read().answers.size == if (isBackup) 2 else 0)
            }
        }
    } finally {
        Files.list(folder).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(folder)
    }
    println("Exported and reimported two synthetic reference packages using SQLite and Rust FSRS")
}
