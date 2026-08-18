plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

android {
    namespace = "frb.axeron.server"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    implementation(libs.rikka.parcelablelist)
    annotationProcessor(libs.rikka.refine.annotation.processor)
    implementation(libs.rikka.refine.runtime)
    implementation(libs.rikka.refine.annotation)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    implementation(project(":api"))
    implementation(project(":aidl"))
    implementation(project(":shared"))
    implementation(project(":rish"))
}
