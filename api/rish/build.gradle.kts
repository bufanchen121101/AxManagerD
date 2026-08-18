plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "frb.axeron.rish"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = project.file("src/main/cpp/CMakeLists.txt")
//            version = "3.31.0"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        prefab = true
    }
}

dependencies {
    implementation(project(":aidl"))
    implementation(libs.androidx.annotation)
    implementation(libs.libcxx)
}