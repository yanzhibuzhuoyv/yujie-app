# 玉絜 · CQMU Assistant

重庆医科大学（CQMU）校园助手 Android 应用，Kotlin + Jetpack Compose 单模块 Gradle 工程。

> 非官方第三方客户端，与学校官方无关。仅供个人学习与自用。

## 1. 构建环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 及以上 | `sourceCompatibility` / `targetCompatibility` 为 17；本仓库实测环境为 **JDK 23**，可正常构建 |
| Android SDK Platform | **android-36** | `compileSdk = 36`，必须已安装否则配置阶段直接失败 |
| Android Build Tools | 36.0.0 | |
| Gradle | 9.5.0 | 由 wrapper 自动下载，无需预装 |
| Android Gradle Plugin | 9.3.0 | 由 Gradle 从 `google()` 仓库解析 |
| Kotlin | 2.3.21 | 含 Compose 编译器插件 |
| 网络 | 需可访问 `google()` / `mavenCentral()` | 首次构建需下载依赖 |

工程内**不含** SDK 路径配置，需自行创建 `local.properties`（该文件已被 `.gitignore` 排除）：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

或设置环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 指向 SDK 根目录。

## 2. 构建命令

Windows：

```powershell
.\gradlew.bat :app:assembleDebug        # 构建 debug APK
.\gradlew.bat :app:assembleRelease      # 构建 release APK（需先配签名，见第 4 节）
.\gradlew.bat :app:testDebugUnitTest    # 运行单元测试（实测 18 个测试类 / 164 个用例）
.\gradlew.bat :app:lintDebug            # 静态检查
.\gradlew.bat clean                     # 清理产物
```

macOS / Linux 把 `.\gradlew.bat` 换成 `./gradlew`。

产物位置：

```
app/build/outputs/apk/debug/app-debug.apk              # 实测约 21.4 MB
app/build/outputs/apk/release/app-release.apk
app/build/reports/tests/testDebugUnitTest/index.html   # 测试报告
app/build/reports/lint-results-debug.html              # lint 报告
```

安装到已连接设备：

```powershell
.\gradlew.bat :app:installDebug
# 或
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 3. 构建流水线

```
settings.gradle.kts        pluginManagement + dependencyResolutionManagement（google / mavenCentral）
        │                  rootProject.name = "CQMUAssistant"，include(":app")
        ▼
build.gradle.kts           声明插件版本：AGP 9.3.0、Kotlin Compose 插件 2.3.21（apply false）
        ▼
app/build.gradle.kts       读取签名配置 → android {} 编译参数 → dependencies {}
        ▼
Kotlin 编译 (compileDebugKotlin) ──┐
Compose 编译器插件处理 @Composable ─┤
资源处理 (AAPT2)                  ─┼─► Dex (D8) ─► 打包 ─► 签名 ─► APK
AndroidManifest 合并              ─┘
```

几个关键点：

- **Compose 编译器**：通过 `plugins { id("org.jetbrains.kotlin.plugin.compose") }` 启用，与 Kotlin 2.3.21 版本绑定；无需手工设置 `composeOptions.kotlinCompilerExtensionVersion`。
- **依赖版本写法**：工程**没有** `gradle/libs.versions.toml` version catalog，版本号全部硬编码在 `app/build.gradle.kts` 内；Compose 系列由 BOM `androidx.compose:compose-bom:2026.06.01` 统一约束，因此 `material3`、`material-icons-extended` 等不写版本号。
- **Release 不做代码压缩**：`release` 构建类型 `isMinifyEnabled = false`，因此 R8 不参与混淆缩减，release 与 debug 运行逻辑一致（测试分发阶段的刻意选择）；`proguard-rules.pro` 仅有 OkHttp / Conscrypt 的 `-dontwarn` 规则。
- **Lint 会阻断构建**：`lint { checkReleaseBuilds = true; abortOnError = true }`，即 `assembleRelease` 期间若出现 lint error 会直接失败，需要先修问题而不是跳过。
- **编译产物不入库**：`.gitignore` 排除 `.gradle/`、`build/`、`app/build/`、`.kotlin/`、`local.properties`。

## 4. Release 签名

仓库**不包含**签名密钥。签名四项参数由 `secretSetting()` 从 Gradle 属性或环境变量读取，**缺任意一项 release 构建即失败**（`validateSigningRelease` 任务中有 `check(releaseSigningConfigured)` 显式断言）：

```powershell
$env:YUJIE_RELEASE_STORE_FILE     = "C:\path\to\release.keystore"
$env:YUJIE_RELEASE_STORE_PASSWORD = "..."
$env:YUJIE_RELEASE_KEY_ALIAS      = "..."
$env:YUJIE_RELEASE_KEY_PASSWORD   = "..."
.\gradlew.bat :app:assembleRelease
```

也可写入用户级 `~/.gradle/gradle.properties`（勿提交仓库）。`.gitignore` 已排除 `release.keystore`、`keystore.properties`、`local.properties`。

> 覆盖升级提示：Android 要求同一 `applicationId` 的升级包必须用同一密钥签名。换密钥会导致老用户无法覆盖安装，只能卸载重装。

## 5. 目录结构

```
settings.gradle.kts / build.gradle.kts / gradle.properties
gradle/wrapper/                     Gradle wrapper（9.5.0）
app/
├── build.gradle.kts                模块构建脚本：SDK 版本、签名、依赖
├── proguard-rules.pro
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/cqmu/assistant/
    │   │   ├── App.kt / MainActivity.kt / PrivacySanitizer.kt
    │   │   ├── data/                数据层：教务仓库与解析策略、会话存储
    │   │   │   ├── ekta/            第二课堂接口
    │   │   │   └── laundry/         洗衣房接口
    │   │   └── ui/                  Compose 界面与 ViewModel
    │   └── res/                     图标与字符串资源
    └── test/
        ├── java/                    JUnit 单元测试（解析器、策略、ViewModel）
        └── resources/probe/         脱敏后的真实页面样本，供解析测试使用
artwork/                             App 图标源文件
```

## 6. 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| `androidx.compose:compose-bom` | 2026.06.01 | Compose 版本统一约束 |
| `androidx.activity:activity-compose` | 1.13.0 | Compose Activity 入口 |
| `androidx.compose.material3:material3` | BOM 托管 | Material 3 组件 |
| `androidx.compose.material:material-icons-extended` | BOM 托管 | 扩展图标集 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.10.0 | ViewModel 与 Compose 集成 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.10.0 | `collectAsStateWithLifecycle` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | 协程 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP 客户端 |
| `org.jsoup:jsoup` | 1.18.3 | HTML 解析 |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | 凭据加密存储 |
| `junit:junit` | 4.13.2 | 单元测试（test） |
| `org.json:json` | 20260719 | 测试中构造 JSON（test） |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | 协程测试（test） |

## 7. 常见构建问题

| 现象 | 原因与处理 |
|------|-----------|
| `SDK location not found` | 缺 `local.properties`，按第 1 节创建，或设 `ANDROID_HOME` |
| `Failed to find target with hash string 'android-36'` | 未安装 platform 36，用 SDK Manager 安装 |
| `Execution failed for task ':app:validateSigningRelease'` → `Release signing is not configured.` | 第 4 节四个变量未设全，属预期行为（真实报错原文） |
| `Unsupported class file major version` / JDK 相关报错 | 当前 JDK 与 Gradle 9.5 不兼容，改用 JDK 17 或 21 |
| 依赖下载超时 | 首次构建需访问 `google()` / `mavenCentral()`，检查网络或代理 |
| 中文注释或字符串乱码 | `gradle.properties` 已设 `-Dfile.encoding=UTF-8`；确认 IDE 与控制台同为 UTF-8 |
| `Unable to strip the following libraries ... libandroidx.graphics.path.so` | debug 构建的正常警告，不影响产物 |
| `LocalClipboardManager is deprecated` | 编译期弃用警告，不影响构建 |

## 8. 构建验证记录

本仓库的构建说明均在下列环境中实际执行通过，非纸面推导：

```
环境：Windows / JDK 23.0.2 / Gradle 9.5.0 (wrapper) / AGP 9.3.0 / Kotlin 2.3.21
      compileSdk 36 + build-tools 36.0.0

:app:assembleDebug        BUILD SUCCESSFUL in 46s   → app-debug.apk (21,395,237 字节)
:app:testDebugUnitTest    BUILD SUCCESSFUL in 21s   → 164 用例，0 失败，1 跳过
:app:lintDebug            BUILD SUCCESSFUL in 1m20s → lint-results-debug.html
:app:assembleRelease      预期失败（未配置签名变量），报错文案见第 7 节
```

## 9. 功能概览

课表、成绩、考试安排、空闲教室、洗衣房、第二课堂（二课）六个模块，登录走学校统一身份认证 CAS（支持验证码，凭据经 `EncryptedSharedPreferences` 加密存储）。细节以 `app/src/main/java/com/cqmu/assistant/` 内代码为准。

## 10. 免责声明

- 本项目为个人学习用途的第三方客户端，**非学校官方应用**，不代表学校立场。
- 应用访问的是学校教务等系统，接口结构可能随学校系统更新而失效；请遵守学校相关使用规定。
- 使用本应用产生的任何后果由使用者自行承担。
