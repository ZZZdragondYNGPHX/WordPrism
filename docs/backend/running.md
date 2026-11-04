# 构建与接入

业务核心位于独立的 `backend/` 构建，已通过 `android/sdk-codex` 接入 Claude 的 Compose 前端。应用默认使用真实 Room、Keystore、WorkManager 与 Rust FSRS；`sdk-mock` 仅保留为 debug 开发依赖，release 不包含模拟业务类。视觉与组件沿用 Claude 的实现。正式契约入口见 [contract.md](contract.md)。

## 固定组合

| 项目 | 本实现使用 |
| --- | --- |
| Kotlin / kotlinx.serialization / coroutines | 2.2.21 / 1.9.0 / 1.10.2 |
| Gradle / Android Gradle Plugin / KSP | 8.14.3 / 8.13.2 / 2.2.21-2.0.5 |
| 构建 JDK / 字节码 | JDK 21 / JVM 17 |
| Android | minSdk 26、compileSdk 36、arm64-v8a 与 x86_64 |
| Room / WorkManager / OkHttp | 2.8.4 / 2.10.1 / 4.12.0 |
| 记忆内核 / NDK | fsrs-rs 6.6.2，FSRS-6 / 28.2.13676358 |

`memory-native/Cargo.lock` 固定 Rust 依赖。Windows 主机测试使用 GNU Rust 工具链及 MinGW 运行库；Android 库由 NDK clang 链接，不带 MinGW。所有工具、SDK、下载缓存和调试产物均不纳入 Git。

本机使用 Rust / Cargo `1.99.1`。Claude 工程保留独立基线：Gradle `9.8.2`、AGP `9.5.0`、Kotlin `2.4.21`、Compose BOM `2026.09.00`、serialization `1.12.0`、coroutines `1.11.0`、JDK `25.0.3`，compileSdk `37`、targetSdk `36`、minSdk `26`。最终 APK 的运行依赖以该工程的 release resolution 为准，已验证与后端构件互操作。

应用明确只打包 `arm64-v8a` 与 `x86_64`，避免其他依赖带入缺少记忆内核的 32 位 ABI。当前 Android 运行证据是 API 35 x86_64 模拟器；最低版本声明和 arm64 构建不等于这些设备已经实测。

## 运行

从 `backend/` 执行，确保 `JAVA_HOME` 指向 JDK 21，`cargo`、`rustup` 及宿主链接器在 `PATH`，Android 构建另设置 `ANDROID_HOME` 和 NDK 路径：

```powershell
./scripts/build-native.ps1 -RunTests -Android -NdkPath "$env:ANDROID_HOME/ndk/28.2.13676358"
./gradlew.bat :core-engine:test :platform-jvm:test :memory-core:test :contract-fixtures:run
./gradlew.bat -Pandroid=true :platform-android:assembleRelease :platform-android:assembleDebugAndroidTest
./gradlew.bat -Pandroid=true :platform-android:connectedDebugAndroidTest
```

最后一条需要实际连接的设备或正在运行的模拟器。Linux/macOS 可直接使用 `cargo` 对应目标和 `./gradlew`；本次 Windows 构建证据不能替代那些主机环境的实测。

契约样例生成器接受输出目录参数；正式样例目录为 `docs/backend/fixtures/`，默认目录将由构建配置指向它。宿主集成测试使用临时 SQLite 数据库和模拟模型响应，不需要凭证。

生成器也会创建并导回两个公开的[正式参考包](packages/README.md)。仅更新 schema / 夹具、保留参考包稳定 ID 时执行：

```powershell
./gradlew.bat -PskipReferencePackages :contract-fixtures:run
python scripts/validate-contract.py
```

第二条需要当前 Python 环境安装 `jsonschema`。普通构建不需要 Python。指定一个设备运行隔离的 Android 平台测试（不会清空应用资料）可用：

```powershell
./scripts/verify-device.ps1 -Serial emulator-5556 -ProcessRestart
```

该脚本要求先构建 `:platform-android:assembleDebugAndroidTest`，包含真正的 `am force-stop` 与新 PID 读取检查。

## Compose / Kotlin 接入

Android 平台入口是 `dev.wordprism.android.AndroidWordPrism.open(applicationContext)`，返回同一个应用级实例。`open` 在新进程中恢复持久状态，并安排可执行任务。当前 `SdkProvider` 异步打开核心并提供可重试的启动错误，Compose 页面经 `WordPrismSdk` 门面使用挂起方法与 `Flow`，不持有 Room DAO。直接 Kotlin 客户端也可调用核心：

```kotlin
val sdk: WordPrism = AndroidWordPrism.open(applicationContext)
val reply = sdk.execute(Request(persistedRequestKey, Command.SubmitIntake("linear / liner")))
val task = (reply.value as? View.OperationResult)?.operation
sdk.observe().collect { revision -> /* refresh relevant queries */ }
```

同一次用户动作在重试/重建页面时复用请求键。作答另携带 `currentPresentationId`，后端提供第二层重复提交保护。页面退出可以取消订阅；只有用户明确取消业务操作时调用 `CancelOperation`。

文件选择器由前端接入，后端直接接收 `contentResolver.openInputStream(uri)` / `openOutputStream(uri)`。调用方负责关闭文件流。凭证走 `setCredential`，参数在完成后被清零；不能把凭证明文放进序列化请求、草稿或日志。

如果前端和后端 Gradle/AGP 基线不同，先发布本地 Maven 产物，不要求前端更换构建版本：

```powershell
./gradlew.bat -Pandroid=true :core-contract:publish :memory-core:publish :core-engine:publish :platform-jvm:publish :platform-android:publish
```

前端已配置 `backend/build/repository` 的本地 Maven 地址，并依赖 `dev.wordprism:platform-android:0.4.0`。这会带上同源契约、核心 JAR、平台 AAR、两个 JNI 库、许可证资产及运行依赖；单独复制 AAR 不包含所有传递依赖。`platform-jvm` 供适配器的真实 SQLite/JNI 集成测试使用。

发布后切到 `android/`，将 `JAVA_HOME` 改为前端使用的 JDK 25，并把 `ANDROID_HOME` 指向包含 platform 37 的 SDK，再执行：

```powershell
./gradlew.bat :sdk-codex:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am start -n app.wordprism/.MainActivity
```

使用自己的设备时替换 serial。Debug APK 可直接安装；`app/build/outputs/apk/release/app-release-unsigned.apk` 是未签名 release 构建，尚未配置发行签名或上架。

首次运行是空词库。可在“设置 → 导入与导出”选取 `docs/backend/packages/example-content.wordprism`，离线学习三个示例词；该文件应先复制到设备可选的目录。真实 AI 功能在“设置 → API 预设”填写 HTTPS base URL、model 和 API key，按功能或默认绑定，连接测试本身会请求供应商。日终自动分析默认开启，未配置凭证时只保存待配置任务；完整备份恢复后自动调用关闭，须按需要重新开启。

模型标识可手动填写，也可输入服务地址与密钥后点击“拉取模型”，搜索、选择并保存。已有预设可复用本机凭证；服务商没有 `/models` 时仍支持手动填写。功能与契约见[模型目录](api-protocols.md#拉取模型)。

预设的“思维程度”可使用模型默认或显式档位，修改后先保存再测试。服务地址兼容 API 根地址与完整端点；具体映射、旧数据兼容和可分享样例见[API 接入](api-protocols.md#思维程度)。

AI 添加比连接测试需要更长时间。test.5 修复了读取阶段仍在 10 秒提前超时的问题；如果旧任务已失败，修改超时等参数并保存后，在该任务点击“重试”即可采用已保存版本。每次尝试仍分别记录来源与已知用量，见[超时与重试](api-protocols.md#失败取消与用量)。

AI 添加页的明确按钮点击会提交当前可见文本，清除输入法保留的候选状态；键盘回车仍用于输入，不会自动发起 API 请求。该行为与学习页一致，保持原有 Compose 控件和任务反馈。

新增的模型选择器 Compose 仪器测试使用合成列表，不查询供应商。可在 `android/` 执行 `:app:assembleDebugAndroidTest`，安装 debug APK 和 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` 后，只对指定设备运行：

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class app.wordprism.ui.settings.ModelPickerTest app.wordprism.test/androidx.test.runner.AndroidJUnitRunner
```

读取或启动失败通过 `observeErrors()` 呈现，`retryReads()` 保留原订阅重试。文件、模型响应与统计均不回落到模拟数据。

非 Kotlin 前端可用 `dev.wordprism.engine.JsonBridge` 包装同一个 `WordPrism`。桥只做同源 JSON 序列化；所有业务仍在核心里。

## 运行边界

- API 支持 `openai_chat_completions` 和原生 `openai_responses`，在现有预设中选择；`baseUrl` 可填写 API 根路径或完整端点。请求、用量、失败、重试及配置样例见[LLM 协议接入](api-protocols.md)。
- 一个模型任务最多 50 个词；手动添加最多 200 个。资料库限制 10,000 词、100,000 作答、30,000 题目；专属包压缩 32 MiB、展开 64 MiB。超限会明确失败。
- 个人拟合在本机执行，单线程、5 个 epoch、最长 256 条目标历史、最多 5,000 个样本、30 秒可取消预算。选取近期样本并保留各目标首次延迟前缀，避免把中段历史当作首次学习；按时间切分评估，数据不足保留原参数。
- SQLite/Room 按稳定身份存储独立记录，只写变化行；当前事务仍读取并解码资料库快照。目标规模的响应时间和内存需要设备验证，不能用桌面测试推断真机性能。
- Compose 当前每 15 秒报告前台计时；超过 90 秒未确认的单次间隔不继续累计。冷启动不补算未确认的尾段。生成等待、后台和学习分别保存。
- 所有实际作答和来源记录保留。撤销新增内容使用归档/停用而非删除被历史引用的实体；策略回退重放包含后续作答的历史。
- 没有云同步。WorkManager 仅负责唤醒，业务去重和结果提交资格由持久核心再次校验。任务产生不保证系统立即安排运行。

实际执行过的测试、构建和设备证据见 [validation.md](validation.md)，不将模拟模型响应算作真实 API 验证。

## 源码与许可

采用的是实际发布的 [`fsrs` 6.6.2 crate](https://crates.io/crates/fsrs/6.6.2)，其 VCS 记录为 `6088949b99d44f46f5328fd7289d209b9a842b68`。核对了调度、优化器、拟合数据前缀及评估源码，直接通过 JNI 使用该 Rust 内核；没有另写一套 Kotlin FSRS。Room、WorkManager、OkHttp 等版本通过实际 Maven 构件、POM/源码与 Android 构建核验。

完整清单位于 [third-party/wordprism-notices](../../backend/third-party/wordprism-notices/README.md)，随 AAR/APK 打包。当前共 249 个组件记录：实际 Android release / 独立后端 Maven 依赖的并集、Cargo.lock 的保守全集与两款字体。`priority-queue` 选择 MPL-2.0，并附原始源码压缩包；其余选用许可及全文见 manifest。

升级依赖后，先分别执行 backend 的 `-Pandroid=true :platform-android:writeRuntimeDependencies` 与 android 的 `:app:writeRuntimeDependencies`，用 `cargo metadata --locked --format-version 1 --manifest-path memory-native/Cargo.toml` 生成本机临时输入，再运行：

```powershell
# 在 backend/；按实际缓存位置重复提供 --gradle-cache
python scripts/collect-notices.py --cargo-metadata build/cargo-metadata.json `
  --runtime-list build/runtime-dependencies.tsv --runtime-list ../android/app/build/reports/runtime-dependencies.tsv `
  --gradle-cache "$env:GRADLE_USER_HOME/caches/modules-2/files-2.1" `
  --font-directory ../android/app/src/main/res/font --font-license-directory ../docs/frontend/licenses --output third-party
```

收集完成后重新发布 AAR 并构建 APK。构建脚本不主动下载/变更许可文件；`collect-notices.py` 对缺失的 `jni-sys-macros` workspace 许可使用发布 VCS commit 的上游原文。含本机路径的 metadata 输入不纳入 Git。最终包检查：

```powershell
python scripts/validate-apk.py ../android/app/build/outputs/apk/debug/app-debug.apk
python scripts/validate-apk.py ../android/app/build/outputs/apk/release/app-release-unsigned.apk --release
```

检查包括两个 ABI 的完整内核、ELF/ZIP 16 KiB 对齐、许可证/源码完整性、字体二进制摘要，以及 release 未包含模拟后端。
