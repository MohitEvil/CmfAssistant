# sherpa-onnx (neural voice): its Kotlin classes are bound to native code and reached by reflection.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# commons-compress mentions optional compression libraries we don't use.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**

# The native engine calls the audio callback by method name, so it must keep its exact shape.
-keep class com.demon.cmfassistant.audio.SamplesCallback { *; }
-keep class kotlin.jvm.functions.Function1 { *; }
