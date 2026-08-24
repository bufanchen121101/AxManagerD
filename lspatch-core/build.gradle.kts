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
    // apkzlib's SigningExtension needs BouncyCastle at runtime to produce the APK signature block.
    // Its BC is a runtime-scope dependency, which never makes it into an Android APK on its own, so
    // it is declared here explicitly - aligned to the adb module's 1.84 to avoid duplicate classes.
    api("org.bouncycastle:bcpkix-jdk18on:1.84")
    compileOnlyApi("com.google.auto.value:auto-value-annotations:1.10.1")
    annotationProcessor("com.google.auto.value:auto-value:1.10.1")
}
