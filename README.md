# 玉絜 · CQMU Assistant

重庆医科大学（CQMU）校园助手 Android 应用，使用 Kotlin + Jetpack Compose 编写。

> 非官方第三方客户端，与学校官方无关。仅供个人学习与自用。

## 功能

| 模块 | 说明 |
|------|------|
| 课表 | 教务系统课表拉取与本地展示，含当前时间导航、节次时间映射 |
| 成绩 | 成绩与学分绩点查询 |
| 考试 | 考试安排查询，含展示策略过滤 |
| 空教室 | 按校区 / 教学楼 / 节次查询空闲教室 |
| 洗衣房 | 洗衣机状态查询（海尔 H5 接口） |
| 二课 | 第二课堂活动与成绩明细（Ekta 接口） |
| 登录 | 教务系统登录，支持 CAS 跳转与验证码；凭据经 AndroidX Security 加密存储 |

## 技术栈

- Kotlin，Jetpack Compose（Material 3），BOM `2026.06.01`
- `compileSdk` / `targetSdk` 36，`minSdk` 26，Java 17
- OkHttp 4.12 + Jsoup 1.18（教务系统抓取与 HTML 解析）
- AndroidX Security Crypto（凭据加密存储）、Lifecycle ViewModel Compose
- JUnit 4 + kotlinx-coroutines-test（单元测试）

## 构建

需要 JDK 17 与 Android SDK（platform 36）。

```powershell
.\gradlew.bat :app:assembleDebug     # debug 包
.\gradlew.bat :app:testDebugUnitTest # 单元测试
```

## Release 签名

仓库**不包含**签名密钥。`assembleRelease` 的签名信息只从本机 Gradle 属性或环境变量读取，缺任一项会直接构建失败：

```powershell
$env:YUJIE_RELEASE_STORE_FILE     = "C:\path\to\release.keystore"
$env:YUJIE_RELEASE_STORE_PASSWORD = "..."
$env:YUJIE_RELEASE_KEY_ALIAS      = "..."
$env:YUJIE_RELEASE_KEY_PASSWORD   = "..."
.\gradlew.bat :app:assembleRelease
```

也可写入用户级 `~/.gradle/gradle.properties`（勿提交到仓库）。`.gitignore` 已排除 `release.keystore`、`keystore.properties`、`local.properties` 及本地真机验证证据目录。

## 目录结构

```
app/src/main/java/com/cqmu/assistant/
├── data/            数据层：教务、二课、洗衣房仓库与解析策略
│   ├── ekta/        第二课堂
│   └── laundry/     洗衣房
└── ui/              Compose 界面：home / timetable / grade / exam / freeroom / laundry / ekta / login
artwork/             App 图标源文件
```

## 免责声明

- 本项目为个人学习用途的第三方客户端，**非学校官方应用**，不代表学校立场。
- 应用访问的是学校教务等系统，接口结构可能随学校系统更新而失效；请遵守学校相关使用规定。
- 使用本应用产生的任何后果由使用者自行承担。
