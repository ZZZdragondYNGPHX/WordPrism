# WordPrism Android 前端

日期：2026-11-03 · 前端：Claude · 核心与集成：Codex · 状态：已接入真实本机核心，在 Android 模拟器完成集成流程。

所有业务门面已通过 `sdk-codex` 接入正式契约，默认使用 Room / Keystore / WorkManager / Rust FSRS。绑定与原缺口的处理结果见 [Codex 契约对应](codex-contract-mapping.md)。

本文保留 Claude 的技术选择、视觉方向与页面说明。业务类型及运行行为以[本地后端契约](../backend/contract.md)为准；产品方向与分工见[方案索引](../plan/index.md)和[前后端边界](../plan/frontend-backend-boundary.md)。实际集成证据见[后端验证](../backend/validation.md)，[早期模拟前端验证](validation-2026-11-03.md)保留为历史记录。

## 技术栈与绑定（前端决定）

| 项目 | 选择 | 理由 |
| --- | --- | --- |
| 界面框架 | 原生 Android：Kotlin + Jetpack Compose + Material 3（深度定制主题） | 进程内调用 Kotlin 核心，经门面映射，无跨语言桥 |
| SDK 绑定 | **进程内直接调用 Kotlin 接口** `app.wordprism.sdk.WordPrismSdk`：挂起函数做写操作，`Flow` 观察状态 | 符合边界文档“原生 Android 可直接消费 Kotlin 接口”；不复制业务规则，不需要序列化桥 |
| 导航 | Navigation Compose 类型安全路由 | 路由参数只带稳定 ID |
| 状态 | ViewModel + `SavedStateHandle`；进行中的请求 ID、任务 ID 存入 `SavedStateHandle` | 进程被杀后可重新接上真实任务并保持幂等 |
| 本地草稿 | AI 添加输入框草稿存 DataStore；答题草稿通过 `StudyApi.saveDraft` 交给后端 | 提交前的纯界面草稿不进入业务库 |
| 依赖注入 | 无框架；`di/SdkProvider.kt` 是唯一的替换点 | 规模小，替换点清楚 |
| 字体 | Atkinson Hyperlegible Next / Mono（OFL，随包内置） | 专为区分相似字形设计，切合“分清形似词”的主题；中文用系统字体 |

版本（2026-11-03 固定）：AGP 9.5.0、Gradle 9.8.2、Kotlin 2.4.21、Compose BOM 2026.09.00、Navigation 2.10.2、Lifecycle 2.11.0、compileSdk 37、targetSdk 36、minSdk 26。Gradle 运行在 JDK 25。

## 模块

```
android/
  sdk-contract/  纯 Kotlin/JVM，界面门面；唯一业务类型仍在 backend/core-contract
  sdk-mock/      纯 Kotlin/JVM，debug 开发替身与历史前端测试
  sdk-codex/     纯 Kotlin/JVM，全部门面映射到发布的 dev.wordprism 构件
  app/          Compose 应用；SdkProvider 打开真实核心，debug/release 开发面板分源集
```

应用已经切换为真实核心，`isMock = false` 时开发面板隐藏；release 不包含 `sdk-mock`。启动和订阅读取错误可观察、可重试，不回落到模拟数据。接入保留了原有视觉与组件，修复了明确点击提交时 IME composition 残留导致静默不提交的问题；候选确认与真正提交继续区分。

## 视觉方向

“棱镜”：把看起来一样的词像光一样分开。

- **唯一的强调元素：字母对齐的棱镜条。** 同组的词用等宽字体逐字母对齐；共同字母保持墨色，不同字母取该成员的光谱色并加下划线（不只靠颜色），不同的列有一条淡色竖带。出现在词组页和学习中的“看清差异”。
- **光谱色只表示成员身份。** 同一组里第 1 个词永远是朱红、第 2 个琥珀、第 3 个绿……词库列表、重点辨析、总览中的混淆对都沿用，界面其余部分保持安静。
- 底色为冷调霜紫灰 `#F1F0F6`，文字为靛墨 `#23244A`，操作色靛蓝 `#3A3FC8`；完整的深色主题单独调过光谱色。
- 左对齐、列表行优先，避免卡片堆叠；圆角按层级（内容 14dp、底部面板 28dp、按钮胶囊）。
- 动效只回应操作：棱镜条的分色、反馈面板滑入、题目淡入淡出。系统关闭动画时全部即时完成。

## 页面

| 页面 | 覆盖的需求 |
| --- | --- |
| 今天 | 学习日、30 分钟预算条（后端判定接近/已到预算）、计划与训练比例、重点辨析、AI 调整入口、结束今天（说明每日分析只运行一次） |
| 学习会话 | 判断基础、看清差异、按释义辨认、语境选词、无选项回忆、短文理解、造句；提示（记为练习）、跳过、标记有问题、预算提示、草稿自动保存、暂停即退出、进程被杀后恢复 |
| 词库 / 词组 | 搜索与分类、待整理词、棱镜条对照、编辑（版本冲突提示）、移出成员（可撤销）、推荐词：查看不调用 API，选中后加入（可撤销），主动寻找更多 |
| AI 添加 | 自然语言输入、草稿持久化、调用前说明使用的预设、排队/等待联网/进行中/需要选择/完成/失败的真实状态、取消、重试、撤销 |
| 学习总览 | 与 AI 同一快照；投入、记忆表现（观察值与预测值分开）、混淆对及明细、各项能力（客观判分与 AI 评价分开）、样本少提示、负担预测、API 用量 |
| AI 调整 | 每日自动分析开关、手动分析与重新分析、调整记录（前后值、依据、生效时间、撤销）、可锁定项、由用户决定的每日预算 |
| 设置 | 多个 API 预设（密钥只写不读）、连接测试、功能分配、默认与备用预设、调用额度与重试、用量、导入导出 |
| 导入与导出 | 系统文件选择器（SAF）；完整备份与词组内容包；导入前预览模块、新增/重复/冲突、待配置项；恢复前确认 |
| 任务记录 | 所有后台任务的状态、取消、重试、撤销 |
| 开发：模拟场景 | 仅模拟后端：离线、AI 响应速度、注入失败、接近预算、新学习日、重置演示数据 |

## 构建与运行

先按[构建与接入](../backend/running.md)生成 Rust 内核并发布后端 Maven 构件，再构建 `android/`。Gradle 使用 JDK 25，SDK 需要 platform 37；机器路径放在环境变量或已忽略的 `local.properties`，不纳入源码。

```powershell
cd android
# JAVA_HOME 指向 JDK 25，ANDROID_HOME 指向含 platform 37 的 SDK
.\gradlew.bat :sdk-codex:test :app:assembleDebug :app:lintDebug :app:assembleRelease
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am start -n app.wordprism/.MainActivity
```

替换为自己的已运行设备 serial。默认空词库可通过[正式参考包](../backend/packages/README.md)试用；真实 AI 需要配置用户自己的预设与凭证。

## 模拟与真实的界线

| 部分 | 现状 |
| --- | --- |
| 界面、导航、输入法处理、状态展示、撤销入口 | 真实实现，在模拟器上操作验证过 |
| 文件选择、导出写入、导入读取 | 真实 SAF / ContentResolver 与正式 `.wordprism` ZIP 容器；完整恢复在模拟器操作验证 |
| 词库、评分、计划、预算、调度、统计 | 真实核心，Room 与 Rust JNI；无须 API 的学习已在模拟器执行 |
| AI 添加、归组、反馈、分析 | 实际任务、权限与解析代码；测试采用脚本模型响应或本地 HTTP 服务，未使用真实 LLM API |
| 预设与凭证 | 真实版本化预设、功能绑定与 Keystore 凭证；备份不含密钥，恢复后重新配置 |
| Codex 本地核心 | 全部门面已映射；14 个适配器测试中 8 个运行真实 SQLite/Rust 核心 |
| 持久化与恢复 | 真实 Room，已执行应用强制停止与新进程读取；模拟 JSON 存储仅属于 debug 替身 |

## 已知限制

- 界面文案目前直接写在 Kotlin 中（单一中文界面），尚未抽到 `strings.xml`。
- 没有 Compose 仪器化测试；界面验证是在模拟器上用 adb 手动驱动完成的。
- 没有在真机、横屏、平板上运行；没有用中文输入法实际输入（adb 只能输入 ASCII），组字保护已实现但未经中文输入法验证。
- 没有开启 TalkBack 实际朗读；只检查了无障碍节点树（并修复了一个无标签按钮）。
- 批量任务目前显示不确定进度；没有真实 LLM、arm64 手机或目标规模性能证据。Release 尚未签名。
