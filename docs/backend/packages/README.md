# 参考包

以下示例可导入应用，内容为合成数据。

| 文件 | 用途与内容 |
| --- | --- |
| [example-content.wordprism](example-content.wordprism) | 追加导入 `linear / liner / linen` 三词一组；释义、例句和稳定实体，无个人学习历史 |
| [example-backup.wordprism](example-backup.wordprism) | 同一示例库的完整恢复包，另有两次**合成作答**；会替换当前资料，恢复后自动调用关闭 |
| [example-explanation.wordprism](example-explanation.wordprism) | 追加导入 hospital 的人工示例讲解、词源/助记区分、构词、英英释义和用法；hospitality / hospitable 为待选择词族候选，无个人历史 |

通过 Android “设置 → 导入与导出 → 选择数据包”读取；文件提供方只需能提供输入流。后端会预览用途、模块、版本、重复与冲突，再提交。

## 容器与生成

扩展名为 `.wordprism`，内容为 ZIP，包含 `manifest.json` 与 `modules/<name>.json`。当前容器格式为 `wordprism` / `formatVersion: 1`，已知模块 schema 为 `2`。清单记录包 ID、资料库 ID、用途 `CONTENT` 或 `BACKUP`、创建时间、生产者版本、模块路径/版本/SHA-256/依赖与是否必需。

完整备份包含 `library`、`learning`、`memory`、`analysis`、`api`、`operations` 六个模块；内容包使用 `library`。字段与模块版本定义分别以 [schema](../schemas) 和 [PackageFormat](../../../backend/core-engine/src/main/kotlin/dev/wordprism/engine/Packages.kt) 为准，未知可选内容可保留，不会自动执行。

批量创建内容时，推荐使用 `SaveWords` 分批写入一个资料库，再调用 `exportPackage(PackagePurpose.CONTENT, output)`，由核心生成稳定实体、引用与清单摘要。每次 `SaveWords` 支持最多 200 词；当前总量和文件大小限制见[运行说明](../running.md#运行边界)。专属格式不接受裸 CSV、第三方记忆软件包或任意 ZIP。

参考生成器在 [ReferencePackages.kt](../../../backend/contract-fixtures/src/main/kotlin/dev/wordprism/fixtures/ReferencePackages.kt)。从 `backend/` 运行 `./gradlew.bat :contract-fixtures:run` 会重建样例、schema 和这两个包，生成新的示例 ID。只更新接口样例时加 `-PskipReferencePackages`，以保留已有示例身份。重新生成会改变示例身份；保留原 ID 时使用上述跳过选项。

讲解示例生成器为 [ExplanationPackage.kt](../../../backend/contract-fixtures/src/main/kotlin/dev/wordprism/fixtures/ExplanationPackage.kt)。仅在需要重建讲解包时加 `-PskipReferencePackages -PexplanationReferencePackage`；普通接口样例更新不会更换该包的稳定 ID。导入后从“词库 → 待整理 → hospital → 详解”查看，候选选择加入无需模型请求。
