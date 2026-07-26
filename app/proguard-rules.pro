# Diffuse ProGuard/R8 rules for minified release builds.

# --- kotlinx.serialization ------------------------------------------------
# R8 must not strip/rename the generated $serializer companions or the
# @Serializable DTOs (auth/Drive response models), or JSON decoding throws at
# runtime. These are the rules recommended by the kotlinx.serialization docs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep every @Serializable class in our app package plus its synthetic serializer.
-keep,includedescriptorclasses class com.diffuse.**$$serializer { *; }
-keepclassmembers class com.diffuse.** {
    *** Companion;
}
-keepclasseswithmembers class com.diffuse.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ----------------------------------------------------------------
# OkHttp ships its own consumer rules; these silence benign warnings about
# optional compile-time-only platform integrations it references reflectively.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- ZXing (QR encoding) ---------------------------------------------------
# We use the pure-Java core only; nothing reflective, but keep the API surface.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- Keep BuildConfig (Drive client id/secret are read from here) ----------
-keep class com.diffuse.BuildConfig { *; }
