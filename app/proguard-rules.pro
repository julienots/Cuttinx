# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.superessence.cuttinx.**$$serializer { *; }
-keepclassmembers class com.superessence.cuttinx.** { *** Companion; }
-keepclasseswithmembers class com.superessence.cuttinx.** { kotlinx.serialization.KSerializer serializer(...); }
# Vosk (JNA)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# Media3 uses reflection for some extensions
-dontwarn androidx.media3.**
