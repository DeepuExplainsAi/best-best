# NewPipeExtractor (see https://github.com/TeamNewPipe/NewPipeExtractor#usage)
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.lang.model.**
-dontwarn com.google.re2j.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class app.dkord.** { kotlinx.serialization.KSerializer serializer(...); }
