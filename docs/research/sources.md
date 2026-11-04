# 调研来源登记

访问日期：2026-11-03（America/Los_Angeles）。本文件保存研究结论的来源及读取范围，不保存整站抓取内容或第三方词典数据。

论文以原始发表页的摘要、可访问正文或综述摘要为依据；未将仅读摘要写成全文审查。竞品能力来自公开官方说明，未进行账号内功能或性能实测。文档页面与默认分支可以变化，实施时需要固定实际使用版本。

## 学习研究

### R01

[The Effects of Spaced Practice on Second Language Learning: A Meta-Analysis](https://onlinelibrary.wiley.com/doi/10.1111/lang.12479)

Kim & Webb，2022，Language Learning。已读取出版方摘要及研究范围：48 项实验、3,411 人、98 个效应量；支持间隔练习总体有效，效果受条件调节。它没有比较本项目或验证词组 FSRS 方案。

### R02

[A Review of Laboratory Studies of Adult Second Language Vocabulary Training](https://www.cambridge.org/core/journals/studies-in-second-language-acquisition/article/abs/review-of-laboratory-studies-of-adult-second-language-vocabulary-training/18F0A5D1FFC829CE05931B2EEE83124A)

Rice & Tokowicz，2019 年在线发表。已读取出版方综述摘要及部分参考文献。支持将间隔、检索练习和语义加工结合；研究范围为成人二语实验室词汇训练。

### R03

[Contrasting orthographically similar words facilitates adult second language vocabulary learning](https://www.sciencedirect.com/science/article/pii/S0959475222000032)

Baxter 等，2022，Learning and Instruction 80，101582。已读取出版方 highlights 和摘要。114 名成人，特定实验中对照形似二语词或伪词促进学习；不能外推成所有近义词对照都有效。

### R04

[Contrasting Similar Words Facilitates Second Language Vocabulary Learning in Children by Sharpening Lexical Representations](https://pubmed.ncbi.nlm.nih.gov/34295290/)

Baxter 等，2021。已读取 PubMed 摘要。48 名荷兰语儿童，收益受阅读能力影响；用于提醒基础差异，不作为成人中文母语用户的直接效果估计。

### R05

[Effects of Massing and Spacing on the Learning of Semantically Related and Unrelated Words](https://www.cambridge.org/core/journals/studies-in-second-language-acquisition/article/effects-of-massing-and-spacing-on-the-learning-of-semantically-related-and-unrelated-words/F58BA8D70385603B9C42E408BFCB8A10)

Nakata & Suzuki，2018 年在线、2019 年卷期。已读取出版方摘要和部分文献回顾。133 名日本大学生；相关词引发更多干扰错误，但后测得分没有显著组间差异。不能简化为语义聚类总是有效或总是有害。

## 复习算法与实现

### R06

[Anki Manual — Deck Options / FSRS](https://docs.ankiweb.net/deck-options.html#fsrs)

已读取官方手册，另直接提取 FSRS 段落核对期望保持率、工作量、评分习惯、优化参数和历史数据限制。属于实际产品与算法使用文档，不是对 WordPrism 的效果实验。

第三轮重新读取当前 FSRS 章节，核对 Optimize、Health Check、参数改变与重算日期的区别、Simulator，以及旧 CMRR 功能移除说明。用于个人参数方案；未沿用停止更新教程中的固定样本门槛或旧功能状态。

### R07

[ts-fsrs — package README](https://github.com/open-spaced-repetition/ts-fsrs/blob/main/packages/fsrs/README.md)

通过 GitHub 文件工具读取维护方根 README 与包 README。读取时说明 `ts-fsrs` v6 支持多个 FSRS 模型版本，默认构造器使用 FSRS-7，Node.js 要求为 `>=24.0.0`，需要保存 card 与 revlog。文档检索中的旧 API 不直接用于新实现。

### R08

[ts-fsrs — LICENSE](https://github.com/open-spaced-repetition/ts-fsrs/blob/main/LICENSE)

已读取完整 MIT 许可证。复用时需保留相应声明；本轮未安装该依赖。

### R09

[ts-fsrs — npm published metadata](https://registry.npmjs.org/ts-fsrs/latest)

通过本地 Node 直接读取公共 npm 元数据；读取时 `version` 为 `6.0.0`、`engines.node` 为 `>=24.0.0`、`license` 为 `MIT`。这是检索当时的发布状态，不是本项目已锁定的依赖版本。

## 相关产品与数据交换

### R10

[Anki Manual — Exporting](https://docs.ankiweb.net/exporting.html)

已读取官方导出说明。文本、`.apkg`、`.colpkg` 保留内容不同，原生包可包含调度和媒体；是否包含取决于导出选项。

### R11

[Anki Manual — Importing Text Files](https://docs.ankiweb.net/importing/text-files.html)

已读取 UTF-8、分隔符、字段映射、重复更新和媒体相关说明。用于导入预览、字段映射与重复识别的设计参照。

### R12

[Mochi — Official Product Page](https://www.mochi.cards/)

已读取官方页面的本地保存、Markdown、语言学习、TTS 与 AI 文本能力说明。宣传中的效果与用户评价没有作为研究证据。

### R13

[Mochi — Importing](https://www.mochi.cards/docs/import-and-export/importing/)

已读取官方 `.mochi`、Anki、Markdown、CSV 导入说明。原生格式可保留历史、附件等；其他格式保留范围不同，Anki 模板迁移存在差异。

### R14

[RemNote — Language Learning](https://www.remnote.com/use-cases/language-learning)

已读取官方语言学习页面。说明语境填空、AI 生成、TTS、SM-2/FSRS 和材料转学习项目。未采用宣传性效果承诺。

### R15

[RemNote — Importing from Anki](https://help.remnote.com/en/articles/6751471-importing-from-anki)

已读取官方帮助页，页面标注 2026-08-15。说明内容、历史导入及 CSS、JavaScript、某些 TTS 和标签等兼容限制。

### R16

[Quizlet — Creating sets by importing content](https://help.quizlet.com/hc/en-us/articles/360029977151-Creating-sets-by-importing-content)

已读取官方帮助页。支持按分隔符组织词条和释义，再粘贴为卡片集合；对较长笔记提供 AI 学习指南路径。

### R17

[Quizlet — Exporting your sets](https://help.quizlet.com/hc/en-us/articles/360034345672-Exporting-your-sets)

已读取官方帮助页。导出自己创建集合的词条和释义在网页操作；说明图片及复制集合的限制。不能据此推断所有未说明功能均不存在。

### R18

[墨墨背单词 — 使用帮助](https://www.maimemo.com/help30)

已读取官方帮助页的记忆反馈、学习量、模式、离线和备份说明。该帮助页没有提供完整内部算法，也没有足够版本信息保证每个细节适用于全部当前客户端。

### R19

[不背单词 — 开发者 App Store 页面](https://apps.apple.com/cn/app/id698570469)

已读取开发者产品介绍和版本更新记录。可核实语境、搭配、近反义词及“关联笔记”说明；未采用用户评论、排名和营销数字作为效果证据，也未将多年前评论当作当前状态。

### R20

[欧路词典 — 官方更新日志](https://www.eudic.net/v4/en/app/history)

已读取更新条目中的 FSRS、自定义 LLM、Ollama、CSV/Anki 导出与 AI 内容说明。页面日期标题存在与正文年份不一致的情况，因此不据其标题推断精确发布时间，也未核实各客户端套餐差异。

## LLM 官方接口资料

### R21

[OpenAI — Structured model outputs](https://developers.openai.com/api/docs/guides/structured-outputs)

已读取官方页面，并直接读取其 Markdown 中 `Handling mistakes` 段落。官方明确说明 Structured Outputs 仍可能包含错误；schema 约束与内容正确性需要分开处理。本轮未进行实际模型调用。

### R22

[DeepSeek — JSON Output](https://api-docs.deepseek.com/guides/json_mode)

已读取官方 JSON Output 指引。需要 `json_object`、相应提示与输出长度设置；文档说明应处理空白结果和截断。未据此假定所有 OpenAI 参数均受支持。

### R23

[Ollama — OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)

已读取官方兼容说明、本地服务示例及接口能力说明。官方定位为 OpenAI API 的子集；本地与云端需要分别核对。

### R24

[Ollama — Structured Outputs](https://docs.ollama.com/capabilities/structured-outputs)

已读取官方 schema 与 `response_format` 说明；页面在本次读取时注明 Ollama cloud 不支持 structured outputs。后续实施需重新核对目标部署方式。

### R25

[Claude — OpenAI SDK compatibility](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)

已读取官方兼容层定位和限制。官方推荐需要完整能力时采用原生 Claude API；兼容层的 `strict`、`response_format` 等行为不能照搬其他服务。

### R26

[Gemini — OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)

已读取官方接入说明与参数映射。支持 OpenAI 库访问，官方建议尚未使用这些库的项目直接调用 Gemini API。具体模型名称不是本项目已确定配置。

## 词汇数据与许可

### R27

[Open English WordNet — Official Site](https://en-word.net/) · [License](https://github.com/globalwordnet/english-wordnet/blob/main/LICENSE.md)

已读取官方 synset 介绍，并通过 GitHub 读取许可证开头及来源声明。资源在 Princeton WordNet 基础上发展，新增资源采用 CC BY 4.0，同时保留底层 WordNet 声明。未下载完整词库。

### R28

[Kaikki — English machine-readable dictionary](https://kaikki.org/dictionary/English/)

已读取英文机器词典页面、数据结构概况与下载说明。页面说明来源为 Wiktionary 抽取，部分后处理下载方式已标记 deprecated；数据接入应核对实际源与版本。

### R29

[Kaikki — Copyright and license](https://kaikki.org/dictionary/index.html)

已读取版权段落，说明数据沿用 Wiktionary 的 CC-BY-SA 和 GFDL 许可。具体数据、音频或媒体接入仍需按相应来源说明保留许可和署名。

### R30

[Cambridge Dictionary API — Terms of Use](https://dictionary-api.cambridge.org/api/terms-and-conditions)

已读取评估许可、产品使用需要独立申请等条款及相关使用限制。不能把评估 key 直接当作完整产品授权。

### R31

[Cambridge Dictionary API — Application Development terms outline](https://dictionary-api.cambridge.org/commercial-agreement)

已读取官方开发协议概述。页面本身说明概述不构成最终合同；项目不据此承诺未来价格、缓存权限或已取得授权。

## 平台与持久化

### R32

[Tauri — What is Tauri?](https://v2.tauri.app/start/)

已读取官方架构与平台介绍。支持 Web 前端、Rust 等后端及多平台，不等于本项目已有安装包或实际跨端适配。

### R33

[Tauri — SQL Plugin](https://v2.tauri.app/plugin/sql/)

已读取官方 SQL 插件说明，并使用 Context7 查询其官方文档源中的 SQLite 与迁移用法。数据库迁移支持事务；业务导入事务仍需由项目实现和验证。

### R34

[MDN — Storage quotas and eviction criteria](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria)

通过本地 Node 直接读取官方文档并提取 best-effort、persistent storage 和清理相关段落。浏览器数据持久化和用户主动清理的边界用于 PWA 方案比较。

### R35

[Electron — Security](https://www.electronjs.org/docs/latest/tutorial/security)

已读取官方架构责任与隔离说明，包括 Chromium/Node 发行维护和远程内容的本地能力边界。用于桌面候选取舍，本轮未安装 Electron。

## Android 与分层补充资料

### R36

[Android Developers — Build an offline-first app](https://developer.android.com/topic/architecture/data-layer/offline-first)

已读取官方离线架构说明。本地持久数据是读取与界面展示的主要来源，网络变化通过数据层处理。用于评估离线学习，不代表本项目已实现同步或断网测试。

### R37

[Android Developers — Save data in a local database using Room](https://developer.android.com/training/data-storage/room)

已读取官方 Room 概述，包括 SQLite 抽象、查询校验和迁移能力。没有锁定或安装库版本，也没有将数据库工具自带能力当作业务正确性的保证。

### R38

[Android Developers — Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files)

已读取 Storage Access Framework、`ACTION_OPEN_DOCUMENT`、`ACTION_CREATE_DOCUMENT` 和 URI 访问说明。用户通过系统选择文档，无需申请全盘存储访问；应用私有目录外的文件可在卸载后保留。

### R39

[Android Developers — Persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)

已读取 WorkManager 的适用范围与任务类型。用于持久工作，不是所有即时执行场景的通用方案，也不保证不间断后台执行。

### R40

[Android Developers — Manage work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)

已读取唯一任务、状态观察和取消说明，并使用 Context7 查询官方文档源。`KEEP` 等策略可防止重复入队，业务写入仍需处理幂等、取消竞态与迟到结果。

### R41

[Android Developers — Define work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

已读取约束、重试退避与加急工作额度说明。锁屏、省电和系统调度可能影响任务执行，不用于承诺后台生成的精确完成时间。

### R42

[Android Developers — Android Keystore system](https://developer.android.com/privacy-and-security/keystore)

已读取密钥保护和硬件支持边界。Keystore 保护加密密钥，可用于 API token 的加密存储方案；不能由此声称所有设备都有硬件保护，或 token 永远无法被提取。

### R43

[Android Developers — Save UI state in Compose](https://developer.android.com/develop/ui/compose/state-saving)

已读取 ViewModel、保存状态和进程死亡相关说明。Bundle 等机制用于小型界面状态；完整学习记录、任务和数据应持久化，而非只保存在 ViewModel。

### R44

[Android Developers — Accessibility defaults in Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

已读取至少 48dp 的触控目标与组件语义说明。用于界面验收要求，本轮没有实际 Android 触控或无障碍测试。

### R45

[Android Developers — Handle input method visibility](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility)

已读取软键盘显隐、焦点和窗口调整说明。本页主要为 Views 示例；如果采用 Compose 或其他框架，需要按相应实现验证，不直接照搬代码。

### R46

[FSRS-Kotlin — README](https://github.com/open-spaced-repetition/FSRS-Kotlin/blob/main/README.md)

通过 GitHub 文件工具读取完整 README，说明实现 FSRS-6，示例使用 `LocalDateTime`，并声称 MIT 许可。根 `LICENSE` 读取返回 404，未核实完整许可证；未采用 README 的“最新版”措辞作为当前全生态版本结论。尚未安装、检查完整源码或验证参考向量，仅作候选记录。

### R47

[Android Developers — Recommendations for Android architecture](https://developer.android.com/topic/architecture/recommendations)

已读取官方分层、数据层与 repository、UI 状态和依赖处理建议，以及测试部分的可见摘录。页面将这些作为需结合项目情况使用的建议。用于职责划分，不把所有示例依赖或测试建议自动转成项目要求。

### R48

[Android Developers — Common modularization patterns](https://developer.android.com/topic/modularization/patterns)

已读取高内聚低耦合、数据模块、功能模块、模块通信与接口／实现分离部分；获取内容截至依赖反转章节开头。可支持通过独立模块向 UI 暴露接口的方案，未声称已完整审查文末所有实施细节。

## 个性化优化补充资料

### R49

[FSRS Optimizer — README](https://github.com/open-spaced-repetition/fsrs-optimizer/blob/main/README.md)

已读取维护方 README，说明使用个人复习记录优化 FSRS，并给出稳定卡片身份、UTC 毫秒时间戳、评分、可选状态与时长，以及时区和学习日起点等数据需求。仅用作机制与数据设计参照，未安装优化器、核查完整源码或运行训练；不采用 README 中关于跨软件效果一致性的宣传作为已验证保证。

### R50

[Awesome FSRS Wiki — The mechanism of optimization](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-mechanism-of-optimization)

旧 wiki 指向新地址后，已读取维护方关于时间序列复习记录、最大似然和时间反向传播的机制说明。页面示例的参数数量与公式属于其说明上下文，不作为当前所选 FSRS 版本的定义。它解释参数拟合，不验证 LLM 自动写权重或本项目 AI 调整的效果。

## 本机调度与参数优化补充资料

### R51

[fsrs-rs — README](https://github.com/open-spaced-repetition/fsrs-rs/blob/main/README.md)

已通过 GitHub 文件工具读取默认分支的完整 README。文档提供调度、`compute_parameters`、评估与模拟能力，并介绍个人参数优化及降低历史前缀复制开销的数据组织方式。用于本机调度和拟合候选判断；未执行示例、验证性能声明或构建 Android 桥接，不将研究模拟的默认规模直接用于手机。

### R52

[fsrs-rs — LICENSE](https://github.com/open-spaced-repetition/fsrs-rs/blob/main/LICENSE)

已读取默认分支完整根许可证，为 BSD-3-Clause。采用时需遵守源代码与二进制再分发的声明要求；本轮未安装依赖或审查完整传递依赖，不能据此声称已完成整个应用的许可检查。

### R53

[fsrs-rs — Cargo.toml](https://github.com/open-spaced-repetition/fsrs-rs/blob/main/Cargo.toml) · [src/lib.rs](https://github.com/open-spaced-repetition/fsrs-rs/blob/main/src/lib.rs)

已读取默认分支包清单和库入口文件。清单当时标识版本 `6.6.2`、Rust edition 2024 与 BSD-3-Clause；入口实际导出 `compute_parameters`、`evaluate_with_time_series_splits`、`MemoryState`、`FSRS` 等能力。默认分支文件不证明这些 API 已包含于同版本号的发布包，正式使用需固定 release 或提交。没有读取全部实现、运行拟合或验证 Android ABI；条件编译中存在 aarch64 支持也不等于本项目已构建成功。

## 获取与使用边界

- 检索中出现的非官方导出服务和用户博客，没有用于证明竞品官方能力。
- Princeton WordNet 原站的本地直连返回 403；相关数据描述与许可改用已成功读取的 Open English WordNet 官方资料，不把失败页面当成已核实证据。
- 个别文献 DOI 抓取失败时，使用已成功读取的出版方文章页。
- API 与 GitHub 检索服务曾短暂连接失败，已对所用关键资料通过恢复后的服务或本地 HTTP 读取补齐。
- 第三轮检索取得的旧 FSRS 教程已声明停止更新；实际方案改用当前官方手册，并跟随优化机制 wiki 的迁移链接读取新地址。
- 第四轮按本机调度与拟合需求补查 `fsrs-rs` 默认分支；当前仅为有来源支持的候选，发布版本、Android 桥接与资源消耗仍待实现验证。
- 本轮验证的是公开资料内容。模型质量、真实接口成功率、学习改善幅度、最终包体和移动设备性能均未实测。
