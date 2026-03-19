# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\KumaTheta\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt

# Strip debug logs
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Room
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Ktor
-keep class io.ktor.** { *; }
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**

# Kotlin Serialization
-keepattributes *Annotation*, EnclosingMethod, Signature
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.json.** { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
