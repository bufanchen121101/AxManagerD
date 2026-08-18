import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption(
            "Xdoclint:none", "-quiet"
        )
    }
}

apply(from = "manifest.gradle.kts")
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
val verCode = findProperty("api_version_code") as Int
val verName = "${findProperty("api_version_name")}.r${gitCommitCount}"

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            ndkVersion = "29.0.14206865"
            defaultConfig {
                minSdk = 26
                targetSdk = 36
                versionCode = verCode
                versionName = verName
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            ndkVersion = "29.0.14206865"
            defaultConfig {
                minSdk = 26
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }
    }
}

subprojects {
    plugins.withId("com.android.library") {
        plugins.apply("maven-publish")

        extensions.findByName("android")?.let {
            (it as LibraryExtension).publishing {
                singleVariant("release")
            }
        }

        afterEvaluate {
            println(
                """
            === PROJECT DEBUG ===
            path            : ${project.path}
            name            : ${project.name}
            parent          : ${project.parent?.name}
            plugins         : ${plugins.map { it.javaClass.simpleName }}
            androidExt      : ${extensions.findByName("android")?.javaClass?.simpleName}
            publishLibrary  : ${findProperty("publishLibrary")}
            ====================
            """.trimIndent()
            )

            val groupIdBase = "dev.frb.axeron"

            val publishLibrary =
                (findProperty("publishLibrary") as? Boolean) ?: false

            if (!publishLibrary) return@afterEvaluate

            val pub = extensions.findByName("publishing")
            println("PROJECT ${project.path} publishingExt = ${pub != null}")

            val group = if (project.parent == rootProject) {
                groupIdBase
            } else {
                "${groupIdBase}.${project.parent?.name}"
            }

            version = findProperty("api_version_code")!!

            println("${project.displayName}: ${group}:${project.name}:${version}")

            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        groupId = group
                        artifactId = project.name
                        version = version
                    }
                }
                repositories {
                    mavenLocal()
                }
            }
        }

    }

}


