# ARMSX3 R8 / ProGuard rules.
#
# ---------------------------------------------------------------------------
# JNI callback surface -- DO NOT REMOVE
# ---------------------------------------------------------------------------
# The core (librpcsx-android.so) reaches back into Java by NAME STRING:
#
#   rpcsx-android.cpp:603  FindClass("net/rpcsx/ProgressRepository")
#   rpcsx-android.cpp:626  FindClass("net/rpcsx/FirmwareRepository")
#   rpcsx-android.cpp:644  FindClass("net/rpcsx/GameRepository")
#   rpcsx-android.cpp:647  FindClass("net/rpcsx/GameInfo")  + GetMethodID ctor
#                                                           + NewObject
#
# These classes carry @Keep on some members, but AndroidX's default rule for it
# is -keepclassmembers, which preserves the MEMBERS and still allows R8 to
# RENAME THE CLASS. A renamed class makes FindClass return null, so the app
# works in debug and dies the moment you ship a minified release -- the worst
# possible failure shape. Keep the classes themselves, by name.
-keep class net.rpcsx.ProgressRepository { *; }
-keep class net.rpcsx.FirmwareRepository { *; }
-keep class net.rpcsx.GameRepository { *; }
-keep class net.rpcsx.GameInfo { *; }

# GameInfo is constructed from native via GetMethodID("<init>", ...) with an
# exact JVM signature, and is also kotlinx-serialized. Keep its shape intact.
-keepclassmembers class net.rpcsx.GameInfo {
    <init>(...);
    <fields>;
}

# RPCSX declares the dlsym'd entry points as `external fun`. The default
# android-optimize config keeps native method names, but the DECLARING class
# must survive too or JNI has nothing to bind to.
-keep class net.rpcsx.RPCSX { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Serialization
# ---------------------------------------------------------------------------
# kotlinx.serialization generates synthetic $$serializer members reached only
# reflectively. Losing them turns saved library/firmware state into silent load
# failures on the first launch after an update.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# OkHttp / Okio (skins, shaders, drivers and covers all ride HttpClient)
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------
# Keep line numbers so a user-reported release crash is traceable, but hide
# original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
