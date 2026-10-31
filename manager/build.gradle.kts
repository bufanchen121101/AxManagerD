plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

// ============ 官方默认 AI（NVIDIA）密钥：构建期注入 + 混淆 ============
// 目标：明文不落源码、不落 git，APK 的 dex 里也不出现可被 `strings` 直接提取的明文。
//
// 取值优先级：
//   1) local.properties 的 officialAiKey（本地构建用；该文件已在 .gitignore 中）
//   2) 环境变量 OFFICIAL_AI_API_KEY（CI 用：GitHub Secret / Variable 注入）
//   3) Gradle 属性 officialAiKey（-PofficialAiKey=...）
// 未取到 -> 注入空串，App 侧判定「官方默认 AI 不可用」，不会拿空 key 去发请求。
//
// 注意：XOR + 十六进制只是「提高提取门槛」（防 grep / strings 一键提取），
// 不是密码学意义上的安全 —— 客户端内置密钥在原理上都可以被逆向提取出来。

fun officialAiKeyPlain(): String {
    // 注意：这里刻意不用 java.util.Properties —— 在 Kotlin DSL 脚本里 `java` 会被
    // 解析成 JavaPluginExtension，写 `java.util.xxx` 直接报 Unresolved reference: util
    // （上一版 CI 就是死在这一行）。改为按行读文本自己解析。
    val localFile = rootProject.file("local.properties")
    val localValue = if (localFile.exists()) {
        localFile.readText()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("officialAiKey") && it.contains('=') }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            .orEmpty()
    } else {
        ""
    }
    if (localValue.isNotBlank()) return localValue.trim()

    val envValue = System.getenv("OFFICIAL_AI_API_KEY").orEmpty()
    if (envValue.isNotBlank()) return envValue.trim()

    return (findProperty("officialAiKey") as String?).orEmpty().trim()
}

/**
 * 与 App 侧 `AIConfigStore.decodeOfficialAiKey()` 严格对应：
 * XOR（掩码按 seed 循环）-> 小写十六进制。改这里必须同步改 App 侧。
 *
 * 为什么用十六进制而不是 Base64：效果等价（`strings` 一样提取不到明文），
 * 但编码表可自己实现，不依赖 java.util.Base64 —— Kotlin DSL 脚本里 `java`
 * 是扩展名，写 `java.util.Base64` 会编译失败；App 侧也顺带避开 Base64 的 API 级别限制。
 */
fun officialAiKeyBlob(): String {
    // 与 App 侧 AIConfigStore.OFFICIAL_AI_KEY_SEED 必须完全一致。
    val seed = "frb.axeron.manager|AxManagerD/axkey/v1"
    val plain = officialAiKeyPlain()
    if (plain.isEmpty()) return ""
    val mask = seed.toByteArray(Charsets.UTF_8)
    val src = plain.toByteArray(Charsets.UTF_8)
    // 十六进制编码：自身实现，不用 java.util.Base64（见上方说明）。
    val hex = "0123456789abcdef"
    val sb = StringBuilder(src.size * 2)
    for (i in src.indices) {
        val b = (src[i].toInt() xor mask[i % mask.size].toInt()) and 0xFF
        sb.append(hex[b ushr 4]).append(hex[b and 0x0F])
    }
    return sb.toString()
}

// ============ 官方默认 AI（中转站动态密钥）：访问密码 构建期注入 + 混淆 ============
// v1.4.0 起官方默认 AI 不再直连 NVIDIA，而是走中转站（地址见 AIConfigStore，脱敏版为占位符）：
//   - 客户端用这里的「访问密码」（AXKEY_PASS）向中转站换取「当日动态密钥」（日钥）；
//   - 日钥每日轮换、任一时刻有 2 把可用（重叠 1 天），仅存在于运行时内存/缓存；
//   - 访问密码本身也以 XOR+hex 混淆后注入（同 officialAiKeyBlob 机制，另起一条 seed，
//     避免两条 blob 共用 mask 带来的互推风险）。
// 取值优先级同 officialAiKeyPlain()：
//   1) local.properties 的 axkeyPass（本地构建用；该文件已在 .gitignore 中）
//   2) 环境变量 AXKEY_PASS（CI 用：GitHub Secret / Variable 注入）
//   3) Gradle 属性 axkeyPass（-PaxkeyPass=...）
// 未取到 -> 注入空串，App 侧判定「官方默认 AI 不可用」，不会拿空密码去换钥。

fun axkeyPassPlain(): String {
    val localFile = rootProject.file("local.properties")
    val localValue = if (localFile.exists()) {
        localFile.readText()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("axkeyPass") && it.contains('=') }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            .orEmpty()
    } else {
        ""
    }
    if (localValue.isNotBlank()) return localValue.trim()

    val envValue = System.getenv("AXKEY_PASS").orEmpty()
    if (envValue.isNotBlank()) return envValue.trim()

    return (findProperty("axkeyPass") as String?).orEmpty().trim()
}

/**
 * 与 App 侧 `AIConfigStore.decodeAxkeyPass()` 严格对应：
 * XOR（掩码按 seed 循环）-> 小写十六进制。改这里必须同步改 App 侧。
 *
 * seed 与 officialAiKeyBlob() 的不同（另起一条）；与 App 侧
 * AIConfigStore.AXKEY_PASS_SEED 必须完全一致。
 */
fun axkeyPassBlob(): String {
    val seed = "frb.axeron.manager|AxManagerD/axkey/pass/v1"
    val plain = axkeyPassPlain()
    if (plain.isEmpty()) return ""
    val mask = seed.toByteArray(Charsets.UTF_8)
    val src = plain.toByteArray(Charsets.UTF_8)
    val hex = "0123456789abcdef"
    val sb = StringBuilder(src.size * 2)
    for (i in src.indices) {
        val b = (src[i].toInt() xor mask[i % mask.size].toInt()) and 0xFF
        sb.append(hex[b ushr 4]).append(hex[b and 0x0F])
    }
    return sb.toString()
}

// ============ 「非模块功能」默认 AI（NVIDIA NIM）密钥：构建期注入 + 混淆 ============
// 需求（v1.4.0）：
//   - 只有「制作模块」里的聊天走中转站（动态日钥）；
//   - 其余所有 AI 功能默认直连 NVIDIA，固定模型 openai/gpt-oss-20b，复用同一把内置密钥；
//   - 这把密钥不向用户展示，dex 内只有 XOR+hex 混淆串。
// 取值优先级：
//   1) local.properties 的 builtinAiKey（本地构建；该文件已在 .gitignore 中）
//   2) 环境变量 BUILTIN_AI_KEY（CI 用）
//   3) Gradle 属性 builtinAiKey（-PbuiltinAiKey=...）
// 未取到 -> 注入空串；App 侧会回退到内置密文常量（AIConfigStore.BUILTIN_AI_KEY_BLOB_FALLBACK），
// 因此不配置任何 Secret 也能出可用包。

fun builtinAiKeyPlain(): String {
    val localFile = rootProject.file("local.properties")
    val localValue = if (localFile.exists()) {
        localFile.readText()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("builtinAiKey") && it.contains('=') }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            .orEmpty()
    } else {
        ""
    }
    if (localValue.isNotBlank()) return localValue.trim()

    val envValue = System.getenv("BUILTIN_AI_KEY").orEmpty()
    if (envValue.isNotBlank()) return envValue.trim()

    return (findProperty("builtinAiKey") as String?).orEmpty().trim()
}

/**
 * 与 App 侧 `AIConfigStore.decodeXorHex()` 严格对应：
 * XOR（掩码按 seed 循环）-> 小写十六进制。改这里必须同步改 App 侧。
 * seed 与另外两条 blob 都不同（互推风险隔离）。
 */
fun builtinAiKeyBlob(): String {
    val seed = "frb.axeron.manager|AxManagerD/ai/builtin/v1"
    val plain = builtinAiKeyPlain()
    if (plain.isEmpty()) return ""
    val mask = seed.toByteArray(Charsets.UTF_8)
    val src = plain.toByteArray(Charsets.UTF_8)
    val hex = "0123456789abcdef"
    val sb = StringBuilder(src.size * 2)
    for (i in src.indices) {
        val b = (src[i].toInt() xor mask[i % mask.size].toInt()) and 0xFF
        sb.append(hex[b ushr 4]).append(hex[b and 0x0F])
    }
    return sb.toString()
}

android {
    namespace = "frb.axeron.manager"
    defaultConfig {
        // [harden] release 默认不可调试。debuggable=true 时 run-as 可直接读应用私有目录
        // (其中含 AI 日钥缓存)，等于把凭证摊开给人看。
        manifestPlaceholders["debuggable"] = "false"
        // 官方默认 AI 密钥（XOR+十六进制 混淆后的 blob，明文不落源码）
        buildConfigField("String", "OFFICIAL_AI_KEY_BLOB", "\"${officialAiKeyBlob()}\"")
        // 官方默认 AI（中转站）访问密码（XOR+十六进制 混淆后的 blob，明文不落源码）
        buildConfigField("String", "AXKEY_PASS_BLOB", "\"${axkeyPassBlob()}\"")
        // 「非模块功能」默认 AI（NVIDIA）内置密钥（XOR+十六进制 混淆后的 blob）
        buildConfigField("String", "BUILTIN_AI_KEY_BLOB", "\"${builtinAiKeyBlob()}\"")
        // 只保留 arm64-v8a（现代 vivo 设备均为 64 位），砍掉 x86/x86_64/armeabi-v7a
        // 三份冗余 native 库（busybox/adb/rish/axeron 等 .so），显著减小 APK 体积。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 双发行版：main = 正常 AxManager UI 主包；manages = 免 root 独立服务端壳。
    //
    // 为什么要有 manages：axeron_server 进程的 dex/assets 来自安装的 APK，
    // 其 ensureScripts() 会用「自己 APK 内 assets/scripts/functions.sh」覆盖设备上的脚本。
    // 若该 APK 里是旧脚本（无 install_plugin 第 4 参数 RUNTIME 分支），
    // 运行时模块就会被装进 plugins/ 而不是 runtime_plugins/（BUG-7）。
    // manages 用同一份源码构建，因此其 assets 自动携带最新 functions.sh。
    flavorDimensions += "edition"
    productFlavors {
        // 注意：flavor 名不能叫 "main"——AGP 内部会用 flavor+buildType 拼 variant 名，
        // 与保留的 src/main sourceSet 撞车，配置阶段直接报
        // "Multiple entries with same key: main=[] and main=[]"。
        // 故 UI 主包 flavor 命名为 official（applicationId 与产物名均与改造前一致）。
        create("official") {
            dimension = "edition"
            applicationId = "frb.axeron.manager"
        }
        create("manages") {
            dimension = "edition"
            applicationId = "frb.axerond.manages"
            // 覆盖安装：设备上旧 frb.axerond.manages 是 versionCode=14800（1.4.8.r349）。
            // 若沿用全局 versionCode=8，install -r 会报 VERSION_DOWNGRADE，必须卸载。
            // 这里给 manages flavor 单独指定 ≥14800 的 versionCode，实现直接覆盖安装。
            // versionName 与原值保持一致，避免部分厂商对 versionName 降级做额外校验。
            versionCode = 15000
            versionName = "1.4.8.r349"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("frb-project")
            manifestPlaceholders["debuggable"] = "true"
        }
        release {
            signingConfig = signingConfigs.getByName("frb-project")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // 双 flavor 的 versionName/versionCode 完全相同（都由根 gradle 统一注入），
            // 若不加后缀，official 与 manages 的 APK 会同名，CI 收集到同一 artifacts/ 目录时互相覆盖。
            // 这里只给「非 official」的 flavor 追加后缀：official 产物名保持原样，不影响既有下载习惯。
            val flavorSuffix = if (!flavorName.isNullOrEmpty() && flavorName != "official") "_${flavorName}" else ""
            outputImpl.outputFileName = "AxManager_v${versionName}_${versionCode}${flavorSuffix}-${buildType.name}.apk"

            val outDir = File(rootDir, "out")
            val mappingPath = File(outDir, "mapping").absolutePath

            assembleProvider.get().doLast {
                // copy mapping.txt kalau minify aktif
                if (buildType.isMinifyEnabled) {
                    copy {
                        from(mappingFileProvider.get())
                        into(mappingPath)
                        rename {
                            "manager-v${versionName}.txt"
                        }
                    }
                }
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
}

dependencies {

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.ksp)
    implementation(libs.colorpicker.compose)

    implementation(libs.compose.coil)
    implementation(libs.appiconloader.coil)
    implementation(libs.appiconloader)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)

    implementation(libs.rikka.compatibility)
    implementation(libs.rikka.parcelablelist)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    implementation(libs.topjohnwu.libsu.core)
    implementation(libs.topjohnwu.libsu.io)

    implementation(libs.gson)
    implementation(libs.markdown)
    implementation(project(":vector-ui"))
    implementation(project(":server"))
    implementation(libs.rikka.refine.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(project(":aidl"))
    implementation(project(":api"))
    implementation(project(":adb"))
    implementation(project(":shared"))
    implementation(project(":provider"))
    implementation(project(":server-shared"))
    implementation(project(":axerish"))
    implementation(libs.sdp.android)
    implementation(libs.material)
    implementation(libs.mmrl.ui)
    implementation(libs.hiddenapibypass)
    implementation(libs.ansi.library)
    implementation(libs.ansi.library.ktx)

    implementation(libs.sheet.compose.dialogs.core)
    implementation(libs.sheet.compose.dialogs.list)
    implementation(libs.sheet.compose.dialogs.input)
    implementation(libs.haze)
}