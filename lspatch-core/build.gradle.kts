plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("commons-io:commons-io:2.20.0")
    implementation("com.beust:jcommander:1.82")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    api("com.google.guava:guava:32.0.1-jre")
    api("com.android.tools.build:apksig:8.0.2")
    // 【修复】不要引入官方 com.android.tools.build:apkzlib。
    // 官方 apkzlib:8.0.2 有 68 个 zip 类，但【不含 NestedZip】；
    // ApkPatcher 使用的 NestedZip / NestedZip$NameCallback 只存在于
    // lspatch.jar 里的上游 fork apkzlib。
    //
    // 关键：lspatch.jar 是 assets 里的运行时 jar，不参与 dex，因此其中的
    // NestedZip 在运行时【找不到】，报
    //   "Failed resolution of: Lcom/android/tools/build/apkzlib/zip/NestedZip$NameCallback"。
    // 这里把 fork apkzlib 的 130 个类提取成 libs/apkzlib-fork.jar 并以 api 引入，
    // 使其真正打进 dex；与 assets 里的 lspatch.jar 不冲突（后者不参与 dex）。
    // apkzlib's SigningExtension needs BouncyCastle at runtime to produce the APK signature block.
    // Its BC is a runtime-scope dependency, which never makes it into an Android APK on its own, so
    // it is declared here explicitly - aligned to the adb module's 1.84 to avoid duplicate classes.
    api("org.bouncycastle:bcpkix-jdk18on:1.84")
    // 打进 dex 的上游 fork apkzlib（含 NestedZip / ZFile / sign 等）
    api(files("libs/apkzlib-fork.jar"))
    // lspatch.jar 仅用于编译期符号（LSPatch / ManifestParser 等）
    compileOnly(files("../manager/src/main/assets/lspatch.jar"))
    compileOnlyApi("com.google.auto.value:auto-value-annotations:1.10.1")
    annotationProcessor("com.google.auto.value:auto-value:1.10.1")
}
