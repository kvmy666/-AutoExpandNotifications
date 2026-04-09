# Xposed/LSPosed — keep all hook classes
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.kvmy666.autoexpand.** { *; }
-dontwarn de.robv.android.xposed.**

# ML Kit / Google Play Services
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Compose runtime reflection
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }
