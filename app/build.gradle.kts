plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun secretSetting(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull

val releaseStoreFile = secretSetting("YUJIE_RELEASE_STORE_FILE")
val releaseStorePassword = secretSetting("YUJIE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secretSetting("YUJIE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = secretSetting("YUJIE_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.cqmu.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cqmu.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "1.6.13"
    }

    buildFeatures { compose = true }

    signingConfigs {
        create("release") {
            // 保留原密钥用于覆盖升级；路径、别名和口令只从本机配置读取。
            storeFile = rootProject.file(releaseStoreFile ?: "missing-release.keystore")
            storePassword = releaseStorePassword ?: ""
            keyAlias = releaseKeyAlias ?: ""
            keyPassword = releaseKeyPassword ?: ""
        }
    }

    buildTypes {
        release {
            // 测试分发阶段关闭 R8，保持 release 与 debug 的运行逻辑一致。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }
}

tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set YUJIE_RELEASE_STORE_FILE, " +
                "YUJIE_RELEASE_STORE_PASSWORD, YUJIE_RELEASE_KEY_ALIAS and " +
                "YUJIE_RELEASE_KEY_PASSWORD as Gradle properties or environment variables."
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 教务系统抓取：HTTP 客户端 + HTML 解析
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    // 密码加密存储
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
