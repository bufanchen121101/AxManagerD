import java.io.FileInputStream
import java.util.Properties

pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"  // 已移除：api.foojay.io 不可达，JDK21 已由 Gradle 自动 provision
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AxManager"
include(":manager")
include(":server")
include(":adb")
include(":server:stub")
include(":reignite")

var root = "api"

val propFile = file("local.properties")
val props = Properties()

if (propFile.canRead()) {
    props.load(FileInputStream(propFile))

    if (props["api.useLocal"]?.equals("true") ?: false) {
        root = props["api.dir"] as String
    }
}

include(":aidl")
project(":aidl").projectDir = file("$root${File.separator}aidl")

include(":api")
project(":api").projectDir = file("$root${File.separator}api")

include(":provider")
project(":provider").projectDir = file("$root${File.separator}provider")

include(":shared")
project(":shared").projectDir = file("$root${File.separator}shared")

include(":server-shared")
project(":server-shared").projectDir = file("$root${File.separator}server-shared")

include(":rish")
project(":rish").projectDir = file("$root${File.separator}rish")

include(":shell")
project(":shell").projectDir = file("$root${File.separator}shell")

include(":runtime")
project(":runtime").projectDir = file("$root${File.separator}runtime")

include(":axerish")
project(":axerish").projectDir = file("$root${File.separator}axerish")
