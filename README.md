# WordPrism

一款围绕个人易混词库设计的 Android 英语学习应用。把 `linear / liner` 这样的词放在一起辨析，再通过回忆、语境选词、短文和造句练习巩固。

词库、学习记录和 FSRS 调度保存在本机，已有内容可离线学习。AI 功能由手机直接调用你配置的模型 API，无需自建服务器。

## 功能

- **易混词辨析**：对照拼写、义项和用法；同一个词可以属于多个组。
- **混合训练**：结合辨认、回忆、阅读和表达，按学习表现安排复习。
- **AI 辅助**：自然语言添词、讲解、练习生成、随身助教和每日分析；普通与托管模式共用学习记录。
- **学习总览**：查看投入、记忆表现、混淆关系和 API 用量；查看、撤销 AI 调整。
- **数据携带**：使用 `.wordprism` 内容包导入词库，或用完整备份迁移学习资料。

## 开始使用

支持 Android 8.0 及以上，提供 `arm64-v8a` 和 `x86_64` 构建。[测试版下载](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/latest)。

首次启动可从首页导入[示例词库](docs/backend/packages/example-content.wordprism)，直接体验离线学习。需要 AI 时，在设置中添加 API 预设；日常操作见[使用指南](docs/guide.md)。

从源码构建见[构建说明](docs/backend/running.md)。当前重构已通过构建、单元测试与 Android 模拟器界面测试，覆盖范围见[验证状态](docs/validation.md)。

## 项目结构

```text
android/    Compose 应用、界面 SDK 与核心适配器
backend/    Kotlin 业务核心、Android 平台接入、Rust FSRS 内核
  scripts/      构建、契约与 APK 校验工具
  third-party/  依赖声明、许可证与必要源码附件
docs/       使用指南、架构、接口和设计说明
```

[文档目录](docs/README.md) · [架构](docs/architecture.md) · [第三方许可](backend/third-party/wordprism-notices/README.md)
