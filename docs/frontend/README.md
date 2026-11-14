# Android 前端

原生 Kotlin / Jetpack Compose 应用，通过 `sdk/adapter` 接入本机核心。页面使用 ViewModel、SavedStateHandle 和 Flow 管理状态，导航参数传稳定 ID。[门面映射](sdk-mapping.md) · [构建](../core/running.md) · [验证](../validation.md)。

## 目录

`app/src/main/java/app/wordprism/ui/` 按功能组织：

| 目录 | 内容 |
| --- | --- |
| `today`、`study` | 普通 / 托管首页、学习、助教 |
| `library`、`intake` | 词库、词组、词条和 AI 添加 |
| `overview`、`tasks` | 学习总览、AI 调整与任务状态 |
| `settings` | API 预设、功能分配、数据导入导出 |
| `common`、`theme` | 共用组件、颜色、字体与尺寸 |

`AppRoot.kt` 组织导航、学习模式入口、控制中心和底部导航。主操作在页面内完成，任务状态由核心提供。

## 视觉与交互

- 浅色采用中性灰底和白色卡片，深色采用黑底与抬升表面；蓝色标识操作，靛紫标识 AI，光谱色只用于区分组内成员。
- 英文使用 Atkinson Hyperlegible Next / Mono，中文使用系统字体。棱镜对照按字母对齐，长词可横向滚动。
- 首页辨析卡组按内容自然增高，每张卡片标题可点按；控制中心支持换行、滚动和系统返回键。
- 切题、作答和输入即时响应；面板使用短暂淡入 / 缩放，实时遵循系统关闭动画设置。
- 交互目标采用至少 48dp；搜索提供输入语义，导航提供选中状态，抽屉与编辑区支持键盘和内容滚动。

视觉参考集中在 [proposals/](proposals)：当前实现采用 [Spatial Deck & Spotlight](proposals/proposal-a-cupertino-pure.html)，其余 HTML 为方案比较稿。

## 状态与输入

已提交作答和任务保存到核心，页面重建按身份恢复。输入框保留 IME 编辑状态；明确点击提交时使用当前可见文本。助教等待期间可继续编辑，持久提交成功后才清空相应草稿。

`SdkProvider` 异步打开核心，启动或读取失败通过可重试状态展示。Debug 保留开发替身，Release 使用正式核心。
