# WordPrism

面向 Android 手机，以个人易混词库为中心，结合辨析、回忆、AI 短文与造句进行混合训练；通过统一学习总览和 AI 自动调整，逐步形成适合个人的学习计划与记忆参数。

`v0.4` 已实现并接入 Android 应用：Claude 的 Kotlin / Compose 前端通过本地 SDK 调用 Codex 的业务核心，默认使用真实 Room、Keystore、WorkManager 和 Rust FSRS。词库、学习历史、调度、统计及参数拟合均在本机；LLM 由手机直接请求用户配置的 API。暂不做云同步。

每天大致投入半小时以内，以记忆质量为先，学习量、题型和计划时长按实际表现调整。自动分析每天学习结束后执行一次，也支持手动触发与关闭；有效变更可以查看和撤销。

## 构建与试用

完整命令、工具版本与环境要求见[构建与接入](docs/backend/running.md)。先在 `backend/` 构建宿主与 Android 原生内核，再发布五个模块到 `backend/build/repository`；最后在 `android/` 构建应用。两个工程保留各自的 Gradle / Kotlin 基线。

应用要求 Android 8.0 / API 26 以上，打包 `arm64-v8a` 与 `x86_64`。Debug APK 可直接安装，release 当前为未签名构建。首次启动是空词库，可在“设置 → 导入与导出”导入[三个示例词的正式内容包](docs/backend/packages/example-content.wordprism)，离线开始学习。AI 功能需要在 API 预设中填写自己的连接与凭证；连接测试也会调用供应商。

可直接下载 [v0.4.0-test.4 测试版 APK](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.4)，包含原生 Responses、模型列表拉取与思维程度，并修复 Responses 连接测试的 JSON 输入错误。可覆盖安装上一测试版。仓库为私有，需要登录有访问权的 GitHub 账号。在预设中输入地址与密钥后，点击“拉取模型”，搜索、选择思维程度并保存；也可继续手动填写模型。

## 已实现

- 稳定词条、义项与组关系；离线混合训练、真实作答历史、FSRS 调度、本机参数拟合与评估。
- AI 添加与持续归组、拼写和归组选择、额外推荐点击加入；持久任务、重复提交保护、取消与可追溯撤销。
- 用户与 AI 共用的总览、日终分析去重、手动与关闭控制；遵守用户设置的策略及内容调整，撤销保留之后的作答。
- Chat Completions 与原生 Responses、模型列表拉取与搜索选择、思维程度、多 API 预设、默认及按功能绑定、备用与重试控制、设备凭证、按实际请求记录用量。
- 正式 `.wordprism` 内容包与完整备份、预览、版本迁移和原子恢复；恢复后关闭自动调用并重新配置凭证。

## 实现入口与验证

- [本地后端契约](docs/backend/contract.md)：唯一业务类型、命令、查询、错误、任务与契约样例入口。
- [LLM 协议接入](docs/backend/api-protocols.md)：Chat Completions / Responses 配置、请求与错误映射、可复用样例。
- [前端对应关系](docs/frontend/codex-contract-mapping.md)、[前端说明](docs/frontend/README.md)：所有门面接口的映射、视觉与模块结构。
- [验证记录](docs/backend/validation.md)：核心与门面测试、Android 平台测试、实际模拟器中的导入、作答、强停恢复、完整备份恢复及协议配置，以及最终 APK 检查。
- [方案索引](docs/plan/index.md)：已确认需求、产品模块和职责边界。
- [第三方源码与许可](backend/third-party/wordprism-notices/README.md)：随应用打包的组件记录、许可与必需源码附件。

模型业务回归使用脚本响应和本地 HTTP 服务；另已通过宿主上的正式适配器与任务执行器，对 OpenAI `gpt-6-luna` 完成真实 Responses / Chat 连接及模型目录验证。真实服务证据限于连接请求，尚无真实 AI 学习任务、物理手机成功运行、arm64 运行或长期效果证据。当前存储事务仍读取资料库快照，大规模响应时间和内存尚待设备测量。具体限制见[验证记录](docs/backend/validation.md#证据边界与限制)。
