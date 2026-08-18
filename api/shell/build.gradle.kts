plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
}

android {
    namespace = "frb.axeron.shell"

    defaultConfig {
        applicationId = "frb.axeron.shell"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            multiDexEnabled = false
        }
    }
    applicationVariants.all {
        outputs.all {
            val outDir = File(rootDir, "out")
            val mappingPath = File(outDir, "mapping").absolutePath
            val dexPath = "${rootProject.project(":axerish").projectDir}/src/main/assets/scripts"


            assembleProvider.get().doLast {
                // copy mapping.txt kalau minify aktif
                if (buildType.isMinifyEnabled) {
                    copy {
                        from(mappingFileProvider.get())
                        into(mappingPath)
                        rename {
                            "cmd-v${versionName}.txt"
                        }
                    }
                }

                // extract classes*.dex dari APK
                copy {
                    val dexFile = zipTree(file(outputFile))
                        .matching { include("classes*.dex") }
                        .singleFile

                    from(dexFile)
                    into(dexPath)
                    rename {
                        "shell_axerish.dex"
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation.jvm)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)
    compileOnly(project(":server-shared"))
}