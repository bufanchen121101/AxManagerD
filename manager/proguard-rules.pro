# ============================================================
# AxManager release R8 rules
# ============================================================

# ---------- 1. 属性 ----------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-renamesourcefileattribute SourceFile

# ---------- 2. Gson 反序列化模型 ----------
-keep class org.lsposed.lspatch.data.model.** { *; }
-keep class org.lsposed.lspatch.data.repository.** { *; }
-keep class frb.axeron.manager.ui.webui.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers enum * { *; }

# ---------- 3. 反射 / AIDL / Shizuku ----------
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.hidden.** { *; }
-keep class rikka.rish.** { *; }
-keep class frb.axeron.** { *; }
-keep class **.Stub { *; }
-keep class **$Stub { *; }
-keep class **$Proxy { *; }
-keep class * implements android.os.IInterface { *; }

# ---------- 4. Parcelable ----------
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepnames class * implements android.os.Parcelable

# ---------- 5. 组件 ----------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.app.admin.DeviceAdminReceiver
-keep public class * extends android.service.quicksettings.TileService
-keep class frb.axeron.manager.owner.** { *; }

# ---------- 6. LSPatch ----------
-keep class org.lsposed.lspatch.** { *; }
-keep class org.lsposed.lspd.** { *; }
-dontwarn org.lsposed.**

# apkzlib / meditor / manifesto 编辑库（纯 Java，反射读取）
# 【修复】apkzlib 全部实现在 lspatch.jar 内（上游 fork，含 NestedZip），
# 必须 keep，否则 R8 混淆/裁剪后运行时会出现
# "Failed resolution of: Lcom/android/tools/build/apkzlib/zip/NestedZip$NameCallback"。
-keep class com.android.tools.build.apkzlib.** { *; }
-keep class com.wind.meditor.** { *; }
-keep class pxb.android.** { *; }
-keep class pxb.android.axml.** { *; }
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue

# ---------- 7. native ----------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}

# ---------- 8. Kotlin / Compose / Room ----------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# ---------- 9. 第三方 ----------
-keep class org.topjohnwu.** { *; }
-keep class com.topjohnwu.** { *; }
-dontwarn org.topjohnwu.**
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 10. dontwarn ----------
-dontwarn android.app.**
-dontwarn android.content.**
-dontwarn android.os.**
-dontwarn android.view.**
-dontwarn android.hardware.**
-dontwarn android.permission.**
-dontwarn com.android.**
-dontwarn android.ddm.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**