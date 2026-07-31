# Apache POI (HSSF only — used for legacy .xls). Suppress warnings for
# desktop-only classes that are never touched on the read path.
-dontwarn org.apache.poi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn org.osgi.**
-dontwarn aQute.bnd.annotation.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-keep class org.apache.poi.hssf.** { *; }
-keep class org.apache.poi.poifs.** { *; }
-keep class org.apache.poi.ss.** { *; }
-keep class org.apache.poi.util.** { *; }

# SQLCipher
-keep class net.zetetic.database.** { *; }

# Tink (via androidx.security-crypto) references compile-time-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Tink reads protobuf-lite message fields via reflection; R8 must not strip or
# rename them, or EncryptedSharedPreferences crashes at startup in release builds.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.karursdo.**$$serializer { *; }
-keepclassmembers class com.karursdo.** {
    *** Companion;
}
-keepclasseswithmembers class com.karursdo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
