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

/** Hand-authored synthetic UI example. It makes no API call and claims no dictionary verification. */
internal suspend fun writeExplanationPackage(directory: File) {
    directory.mkdirs()
    val folder = Files.createTempDirectory("wordprism-explanation-example-")
    val clock = Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"), ZoneId.of("UTC"))
    val credentials = object : CredentialStore {
        override fun has(presetId: Id) = false
        override fun read(presetId: Id): CharArray? = null
        override fun write(presetId: Id, secret: CharArray) { error("No credentials in a synthetic content package") }
        override fun delete(presetId: Id) = Unit
    }
    try {
        SqliteStateStore(folder.resolve("source.db")).use { store ->
            val core = CoreEngine(store, credentials, NativeMemoryKernel(), clock)
            check(core.execute(Request("example-hospital", Command.SaveWords(listOf(
                WordSeed("hospital", "医院", "noun", examples = listOf("She works at a hospital."))
            )))).fault == null)
            store.transaction { db ->
                val sense = db.senses.values.single()
                val source = Provenance("synthetic_example", atMs = clock.millis())
                val candidates = listOf(
                    WordSeed("hospitality", "好客；殷勤招待", "noun") to "同族词示例：强调接待客人的态度或行为。",
                    WordSeed("hospitable", "好客的；热情友好的", "adjective") to "同族词示例：用于描述好客的人或友好的环境。",
                ).mapIndexed { index, (seed, reason) ->
                    Recommendation(Id("example-hospital-family-$index"), seed.spelling, reason, content = seed, source = source, originSenseId = sense.id)
                }
                candidates.forEach { db.recommendations[it.id] = it }
                val content = ExplanationContent(
                    "hospital 指提供医疗照护的医院。可与 hospitality（好客）和 hospitable（好客的）联系记忆，同时留意它们的词性和使用场景。这个包是人工编写的功能示例。",
                    "a place where people receive medical care, especially when they need to stay for treatment",
                    listOf(WordPart("hospit-", WordPartKind.ROOT, "与客人、接待相关的历史词干", "示例展示历史构词；不要把 hospital 机械拆成 host + al。")),
                    EtymologyNote("hospital 的历史可联系到拉丁语 hospes（客人或主人）及接待、照护的含义，现代英语主要指医院。",
                        "人工编写的示例说明，未经本应用核验；具体演变请查词源词典。"),
                    Mnemonic("想象一位热情的主人在医院门口接待来访者：门上的 hospital 提醒你这里提供医疗照护，主人的 hospitality 则提醒你好客与接待。",
                        "医院是场所，好客是态度；这个场景纯属记忆联想。"),
                    listOf("hospital 是名词；hospitable 是形容词，hospitality 是名词。", "常见搭配有 go to hospital / go to the hospital；具体冠词使用受英语变体和语境影响。"),
                    listOf("The new hospital provides care for the whole community.", "We thanked our hosts for their warm hospitality."),
                )
                db.senses[sense.id] = sense.copy(version = 2, explanation = SenseExplanation(2, 2, content, candidates.map { it.id }, source))
                db.words[sense.wordId]?.let { db.words[it.id] = it.copy(version = 2) }
            }
            val file = File(directory, "example-explanation.wordprism")
            file.outputStream().use { check(core.exportPackage(PackagePurpose.CONTENT, it).fault == null) }
            SqliteStateStore(folder.resolve("verify.db")).use { target ->
                val restored = CoreEngine(target, credentials, NativeMemoryKernel(), clock)
                val preview = file.inputStream().use { restored.previewPackage(it) }
                check(preview.fault == null) { preview.fault.toString() }
                check(restored.execute(Request("verify", Command.CommitImport((preview.value as View.ImportPreview).preview.token))).fault == null)
                val senseId = target.read().senses.keys.single()
                val view = restored.query(Query.GetExplanation(senseId)).value as View.Explanation
                check(view.explanation?.content?.mnemonic != null && view.recommendations.size == 2)
                check(target.read().words.size == 1 && target.read().answers.isEmpty() && target.read().presets.isEmpty())
            }
        }
    } finally {
        Files.list(folder).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(folder)
    }
    println("Exported and reimported a synthetic hospital explanation content package; no API request")
}
