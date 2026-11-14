# 构建与测试

先构建 `core/` 并发布本地 Maven 构件，再从仓库根目录构建应用。两个工程使用各自的 Gradle Wrapper。

## 环境

| 工程 | 工具链 |
| --- | --- |
| `core/` | JDK 21、Gradle 8.14.3、Kotlin 2.2.21、AGP 8.13.2、Android SDK 36 |
| 仓库根目录（应用） | JDK 25、Gradle 9.8.2、Kotlin 2.4.21、AGP 9.5.0、Compose BOM 2026.09.00、Android SDK 37 |
| Rust 内核 | Rust stable（本次使用 1.100）、NDK 28.2.13676358、宿主 C/C++ 链接器 |

设置 `ANDROID_HOME` 指向 SDK，按工程切换 `JAVA_HOME`。应用支持 API 26 以上，打包 `arm64-v8a` 与 `x86_64`。内存有限时使用 `--no-parallel --max-workers=2`，构建完成后再启动模拟器。

## 构建本机核心

以下为 Linux 命令，从仓库根目录开始，`JAVA_HOME` 使用 JDK 21：

```bash
cd core
cargo build --manifest-path memory-native/Cargo.toml --release --locked
rustup target add aarch64-linux-android x86_64-linux-android
export WORDPRISM_NDK_BIN="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$WORDPRISM_NDK_BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$WORDPRISM_NDK_BIN/x86_64-linux-android26-clang"
cargo build --manifest-path memory-native/Cargo.toml --release --locked \
  --target aarch64-linux-android --target x86_64-linux-android
./gradlew -Pandroid=true :core-contract:publish :memory-core:publish \
  :core-engine:publish :platform-jvm:publish :platform-android:publish
```

Windows 可用 PowerShell 的 `./scripts/build-native.ps1 -Android -NdkPath "$env:ANDROID_HOME/ndk/28.2.13676358"` 构建 Rust，再运行对应的 `gradlew.bat` 发布命令。macOS 的 NDK 工具目录使用 `darwin-x86_64`。

产物发布到 `core/build/repository`，包含核心、平台库、JNI 和许可附件，供前端与集成测试使用。

## 构建与安装应用

切换到 JDK 25，回到仓库根目录执行：

```bash
./gradlew :sdk-adapter:test :app:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :app:assembleRelease
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n app.wordprism/.MainActivity
```

将 serial 替换为自己的设备。Debug APK 可直接安装；Release 位于 `app/build/outputs/apk/release/app-release-unsigned.apk`，分发前需要签名。首次使用见[使用指南](../guide.md)。

## 测试

核心回归在 `core/` 执行：

```bash
cargo test --manifest-path memory-native/Cargo.toml --release --locked
./gradlew :core-engine:test :platform-jvm:test :memory-core:test
./gradlew -Pandroid=true :platform-android:connectedDebugAndroidTest
```

Compose 完整流程使用独立测试应用。在仓库根目录构建并安装：

```bash
./gradlew -I gradle/isolated-ui-test.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm clear app.wordprism.managedtest
adb -s emulator-5554 shell am instrument -w -r \
  -e notClass app.wordprism.ui.study.LearningLatencyTest \
  app.wordprism.managedtest.test/androidx.test.runner.AndroidJUnitRunner
```

该 init script 仅给 Debug 添加 `.managedtest` 后缀，Release ID 保持 `app.wordprism`。`LearningLatencyTest` 需要另行提供学习备份，常规回归跳过。已执行的结果见[验证状态](../validation.md)。

## 契约与参考包

在 `core/` 生成接口样例并校验（Python 校验需要 `jsonschema`）：

```bash
./gradlew -PskipReferencePackages :contract-fixtures:run
python scripts/validate-contract.py
```

去掉 `-PskipReferencePackages` 会重建内容包和备份包，生成新的示例身份。讲解包使用 `-PskipReferencePackages -PexplanationReferencePackage`，详见[参考包](packages/README.md)。

## 运行边界

- 一次模型任务最多 50 词，`SaveWords` 最多 200 词；资料库限制为 10,000 词、100,000 作答和 30,000 题目。
- 数据包压缩大小最多 32 MiB，展开最多 64 MiB。
- 参数拟合使用单线程、5 个 epoch、每目标最多 256 条历史、最多 5,000 个样本和 30 秒可取消预算。

这些是实现中的资源上限。任务、恢复与计时语义见[核心契约](contract.md)。

## 第三方声明与 APK 校验

第三方声明位于 [core/third-party/wordprism-notices](../../core/third-party/wordprism-notices/README.md)，随 AAR / APK 打包，包含依赖许可证、字体和必要源码附件。

依赖升级后，在两个工程分别运行 `:platform-android:writeRuntimeDependencies`（core 加 `-Pandroid=true`）与 `:app:writeRuntimeDependencies`，然后在 `core/` 收集声明：

```bash
cargo metadata --locked --format-version 1 --manifest-path memory-native/Cargo.toml > build/cargo-metadata.json
python scripts/collect-notices.py --cargo-metadata build/cargo-metadata.json \
  --runtime-list build/runtime-dependencies.tsv \
  --runtime-list ../app/build/reports/runtime-dependencies.tsv \
  --gradle-cache "$HOME/.gradle/caches/modules-2/files-2.1" \
  --font-directory ../app/src/main/res/font \
  --font-license-directory ../docs/frontend/licenses --output third-party
```

使用自定义 Gradle 缓存时替换路径。重新发布并构建后校验 APK：

```bash
python scripts/validate-apk.py ../app/build/outputs/apk/debug/app-debug.apk
python scripts/validate-apk.py ../app/build/outputs/apk/release/app-release-unsigned.apk --release
```

校验包含两个 ABI、ELF / ZIP 16 KiB 对齐、字体与许可附件，以及 Release 的依赖范围。
