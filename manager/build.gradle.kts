plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

android {
    namespace = "frb.axeron.manager"
    defaultConfig {
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
    implementation(project(":lspatch-core"))
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