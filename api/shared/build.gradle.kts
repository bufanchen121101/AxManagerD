plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "frb.axeron.shared"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField(
            "int",
            "VERSION_CODE",
            "${findProperty("api_version_code")}"
        )
        buildConfigField(
            "String",
            "VERSION_NAME",
            "\"${findProperty("api_version_name")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation.jvm)
}

extra["publishLibrary"] = true