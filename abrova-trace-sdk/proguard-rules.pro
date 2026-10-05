# AbrovaTrace Android SDK ProGuard Rules

# Keep SDK public API
-keep class ir.abrova.trace.sdk.AbrovaTrace { *; }
-keep class ir.abrova.trace.sdk.AbrovaTraceConfig { *; }
-keep class ir.abrova.trace.sdk.AbrovaTraceConfig$Builder { *; }

# Keep models
-keep class ir.abrova.trace.sdk.models.** { *; }

# Keep callback interfaces
-keep interface ir.abrova.trace.sdk.** { *; }

# Keep enum values
-keepclassmembers enum ir.abrova.trace.sdk.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
