# ProGuard & R8 optimization rules for AntiGem

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepnames class kotlinx.serialization.PolymorphicSerializer
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Serializable <fields>;
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil Image Loader
-keep class coil.** { *; }
-dontwarn coil.**

# JSch SSH
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Sora Editor
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# Termux Terminal Emulator & JNI
-keep class com.termux.terminal.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Commons Compress & XZ
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }

# Markwon & JLaTeXMath
-dontwarn io.noties.markwon.**
-dontwarn ru.noties.jlatexmath.**
-keep class io.noties.markwon.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

# Guava ListenableFuture
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-dontwarn com.google.common.util.concurrent.**

# Android Architecture Components
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
