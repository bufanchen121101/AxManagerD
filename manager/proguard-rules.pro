# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

##---------------Begin: proguard configuration for Gson  ----------
# Gson uses generic type information stored in a class file when working with fields. Proguard
# removes such information by default, so configure it to keep all of it.
-keepattributes Signature

# For using GSON @Expose annotation
-keepattributes *Annotation*

# Gson specific classes
-dontwarn sun.misc.**
#-keep class com.google.gson.stream.** { *; }

# Application classes that will be serialized/deserialized over Gson
-keep class com.google.gson.examples.android.model.** { <fields>; }

# Prevent proguard from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @JsonAdapter)
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Prevent R8 from leaving Data object members always null
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

##---------------End: proguard configuration for Gson  ----------

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepnames class * implements android.os.Parcelable


-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-assumenosideeffects class java.util.Objects{
    ** requireNonNull(...);
}

#-keep class com.frb.engine.Starter {
#    public static void main(java.lang.String[]);
#}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

##---------------Begin: LSPatch (AXMD) 移植 keep 规则 ----------
# 框架 IPC 表面跨 Binder 到 patched app，其 loader 未混淆，必须保持 AIDL 接口签名
-keep class org.matrix.vector.ipc.** { *; }
-keep class org.lsposed.lspatch.IShizukuService { *; }
# Room 数据库实体/DAO 必须保持（R8 否则破坏 schema 与 Room 运行时反射）
-keep class org.lsposed.lspatch.database.** { *; }
# Gson 序列化的 DTO（PatchRequest/PatchMode/PatchStep/ModuleBinding 等），避免 nd2.c() 反序列化崩溃
-keep class org.lsposed.lspatch.data.model.** { *; }
-keep class org.lsposed.lspatch.share.** { *; }
-keep class org.lsposed.lspatch.Patcher$Options { *; }
-keep class org.lsposed.lspatch.share.LSPConfig { *; }
-keep class org.lsposed.lspatch.share.PatchConfig { *; }
# 注入引擎核心类，避免字段被混淆导致 LSPatch 反射读取失败
-keepclassmembers class org.lsposed.patch.LSPatch {
    private <fields>;
}
# Shizuku / refine / hiddenapi 相关
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**
-keep class org.lsposed.hiddenapibypass.** { *; }
# apkzlib / meditor / manifesto 编辑库（纯 Java，反射读取）
-keep class com.android.tools.build.apkzlib.** { *; }
-keep class com.wind.meditor.** { *; }
-keep class pxb.android.** { *; }
-keep class pxb.android.axml.** { *; }
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
##---------------End: LSPatch keep 规则 ----------
