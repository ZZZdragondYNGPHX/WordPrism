# WordPrism

面向 Android 手机，以个人易混词库为中心，结合辨析、回忆、AI 短文与造句进行混合训练；通过统一学习总览和 AI 自动调整，逐步形成适合个人的学习计划与记忆参数。

`v0.4` 已实现并接入 Android 应用：Claude 的 Kotlin / Compose 前端通过本地 SDK 调用 Codex 的业务核心，默认使用真实 Room、Keystore、WorkManager 和 Rust FSRS。词库、学习历史、调度、统计及参数拟合均在本机；LLM 由手机直接请求用户配置的 API。暂不做云同步。

每天大致投入半小时以内，以记忆质量为先，学习量、题型和计划时长按实际表现调整。自动分析每天学习结束后执行一次，也支持手动触发与关闭；有效变更可以查看和撤销。

## 构建与试用

完整命令、工具版本与环境要求见[构建与接入](docs/backend/running.md)。先在 `backend/` 构建宿主与 Android 原生内核，再发布五个模块到 `backend/build/repository`；最后在 `android/` 构建应用。两个工程保留各自的 Gradle / Kotlin 基线。

应用要求 Android 8.0 / API 26 以上，打包 `arm64-v8a` 与 `x86_64`。Debug APK 可直接安装，release 当前为未签名构建。首次启动是空词库，可在“设置 → 导入与导出”导入[三个示例词的正式内容包](docs/backend/packages/example-content.wordprism)，离线开始学习。AI 功能需要在 API 预设中填写自己的连接与凭证；连接测试也会调用供应商。

可直接下载 [v0.4.0-test.8 测试版 APK](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.8)，修复重点辨析的窄屏排版、跨天旧会话覆盖当天计划及积压数量混淆；计划与负荷预测按近期有效学习用时计算，有待学内容时可在每日预算内增加安排。可覆盖安装上一测试版。仓库为私有，需要登录有访问权的 GitHub 账号。[hospital 讲解示例包](docs/backend/packages/example-explanation.wordprism)可离线体验分段讲解与词族选择。

在预设中输入地址与密钥后，点击“拉取模型”，搜索、选择思维程度并保存；再配置默认预设或按功能绑定。旧任务失败时，先保存修改后的预设，再在任务中点击“重试”。本轮已验证 Responses / `gpt-6-luna / MAX / 16384 tokens / 180 秒` 的实际讲解；`MAX / 8000` 仍可能因推理用量耗尽额度，连接测试成功不能代替业务验证。应用会保留失败记录和用量，不保存截断内容或静默增加付费请求。

## 已实现

- 稳定词条、义项与组关系；离线混合训练、真实作答历史、FSRS 调度、本机参数拟合与评估。
- AI 添加与持续归组、拼写和归组选择、额外推荐点击加入；持久任务、重复提交保护、取消与可追溯撤销。
- 词根词缀、词源说明、助记故事、英英释义、用法与例句；词族候选由用户选择加入。已保存讲解可离线阅读，供同一 AI 分析和后续练习使用，来源、版本、过期状态和撤销可追溯。
- 用户与 AI 共用的总览、日终分析去重、手动与关闭控制；遵守用户设置的策略及内容调整，撤销保留之后的作答。
- Chat Completions 与原生 Responses、模型列表拉取与搜索选择、思维程度、多 API 预设、默认及按功能绑定、备用与重试控制、设备凭证、按实际请求记录用量。
- 正式 `.wordprism` 内容包与完整备份、预览、版本迁移和原子恢复；恢复后关闭自动调用并重新配置凭证。

## 实现入口与验证

- [本地后端契约](docs/backend/contract.md)：唯一业务类型、命令、查询、错误、任务与契约样例入口。
- [LLM 协议接入](docs/backend/api-protocols.md)：Chat Completions / Responses 配置、请求与错误映射、可复用样例。
- [词源与助记](docs/backend/explanations.md)：类型化讲解、词族选择、AI 学习衔接与格式兼容。
- [前端对应关系](docs/frontend/codex-contract-mapping.md)、[前端说明](docs/frontend/README.md)：所有门面接口的映射、视觉与模块结构。
- [验证记录](docs/backend/validation.md)：核心与门面测试、Android 平台测试、实际模拟器中的导入、作答、强停恢复、完整备份恢复及协议配置，以及最终 APK 检查。
- [方案索引](docs/plan/index.md)：已确认需求、产品模块和职责边界。
- [第三方源码与许可](backend/third-party/wordprism-notices/README.md)：随应用打包的组件记录、许可与必需源码附件。

模型业务回归使用脚本响应和本地 HTTP 服务；另已对 OpenAI `gpt-6-luna` 完成真实连接、模型目录、添加与持续归组、推荐、讲解、六种题型生成、表达反馈及日终/手动分析。在 API 35 x86_64 Android 模拟器中，正式 APK 完成实际添加、讲解、失败后采用新版预设重试、离线重开和包含新内容的完整备份恢复。分析使用合成作答，实际因证据不足而保留策略；自动变更及关闭后的迟到结果由脚本测试验证。尚无物理手机、arm64 运行或长期学习收益证据；当前存储事务仍读取资料库快照，大规模性能待测。具体限制见[验证记录](docs/backend/validation.md#证据边界与限制)。
