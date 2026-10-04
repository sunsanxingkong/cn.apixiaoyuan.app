# 保留 serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cn.apixiaoyuan.app.**$$serializer { *; }
-keepclassmembers class cn.apixiaoyuan.app.** {
    *** Companion;
}
-keepclasseswithmembers class cn.apixiaoyuan.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# ===== native 替身类（勿删/勿混淆）=====
# libContentEncoder.so 的 JNI_OnLoad 会 FindClass 这个宿主混淆类并在其上
# RegisterNatives("c", "([B)[B")。类名/方法名/签名是 so 里的硬编码常量：
# 被 R8 重命名或裁剪后，System.load 会因 ClassNotFoundException 直接崩启动。
-keep class com.fenbi.android.leo.imgsearch.sdk.utils.e { *; }
-dontwarn androidx.room.paging.**

# ===== 高低版本玻璃分流（2026-10-04，minSdk 33 → 24）=====
# 背景：miuix-blur / kyant 的类只在 SDK_INT >= 33 时被加载（低版本走自写 CPU 管线）。
# R8 看不到「低版本永远不会执行」这一点，可能把 SDK 守卫分支里的类判为不可达而裁掉；
# 在低版本上这没问题（本来就不执行），但在**高版本**上一旦被裁就会 NoClassDefFoundError。
# 所以两条链路的类都保留：
-keep class top.yukonga.miuix.kmp.blur.** { *; }
-keep class top.yukonga.miuix.kmp.shader.** { *; }
-keep class com.kyant.backdrop.** { *; }
-keep class com.kyant.shapes.** { *; }
# 自写低版本管线的公开入口（Compose Modifier 扩展 / 顶层函数），保持可反射调用与稳定命名
-keep class cn.apixiaoyuan.app.core.design.glass.low.** { *; }
# RenderScript（低版本模糊，API 17+ 系统内建）
# 注意：不能写 `-keep class android.renderscript.**` —— R8 禁止 keep `android.*`
# （"Not allowed to keep android.* classes"）。系统类本就不会被收缩，只需 dontwarn。
-dontwarn android.renderscript.**
